package app.bola.cloud9stratigenai.service;

import app.bola.cloud9stratigenai.dto.GenerateReportRequest;
import app.bola.cloud9stratigenai.dto.ReportStatusResponse;
import app.bola.cloud9stratigenai.dto.ScoutingReportResponse;
import app.bola.cloud9stratigenai.exception.ReportNotFoundException;
import app.bola.cloud9stratigenai.exception.ReportNotReadyException;
import app.bola.cloud9stratigenai.model.ReportRequest;
import app.bola.cloud9stratigenai.model.ScoutingReport;
import app.bola.cloud9stratigenai.repository.ReportRequestRepository;
import app.bola.cloud9stratigenai.repository.ScoutingReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@AllArgsConstructor
public class ReportServiceImpl implements ReportService {
	
	private final ModelMapper mapper;
	private final ObjectMapper objectMapper;
	private final ReportRequestRepository reportRequestRepository;
	private final ScoutingReportRepository scoutingReportRepository;

	@Override
	@Transactional
	public ReportStatusResponse generateReport(GenerateReportRequest request) {
		validateRequest(request);
		ReportRequest reportRequest = mapper.map(request, ReportRequest.class);
		reportRequest.setStatus(ReportRequest.ReportStatus.PENDING);

		ReportRequest savedRequest = reportRequestRepository.save(reportRequest);
		return buildReportStatusResponse(savedRequest, false);
	}
	
	@Override
	@Transactional(readOnly = true)
	public ReportStatusResponse getReportStatus(String requestId) {
		ReportRequest request = findRequestOrThrow(requestId);
		boolean isAvailable = scoutingReportRepository.existsByReportRequestPublicId(requestId);
		return buildReportStatusResponse(request, isAvailable);
	}
	
	@Override
	@Transactional(readOnly = true)
	public ScoutingReportResponse getReport(String requestId) {
		ReportRequest request = findRequestOrThrow(requestId);
		if (request.getStatus() != ReportRequest.ReportStatus.COMPLETED) {
			throw new ReportNotReadyException(requestId);
		}
		ScoutingReport scoutingReport = scoutingReportRepository.findByReportRequestId(request.getId())
				                                .orElseThrow(() -> new ReportNotFoundException("Scouting report data not found for request: " + requestId));
		return mapToResponse(scoutingReport);
	}
	
	private ReportRequest findRequestOrThrow(String publicId) {
		return reportRequestRepository.findByPublicId(publicId)
				       .orElseThrow(() -> new ReportNotFoundException(publicId));
	}
	
	private ScoutingReportResponse mapToResponse(ScoutingReport report) {
		return ScoutingReportResponse.builder()
				       .requestId(report.getReportRequest().getPublicId())
				       .reportType(report.getReportType())
				       .reportTitle(buildTitle(report.getReportType()))
				       .summary(extractSummary(report))
				       .createdAt(report.getCreatedAt())
				       .sections(parseSections(report.getReportData()))
				       .build();
	}
	
	private ReportStatusResponse buildReportStatusResponse(ReportRequest request, boolean reportAvailable) {
		return ReportStatusResponse.builder()
				       .requestId(request.getPublicId())
				       .status(request.getStatus().name())
				       .progress(calculateProgress(request.getStatus()))
				       .currentStep(getCurrentStep(request.getStatus()))
				       .reportAvailable(reportAvailable)
				       .error(getErrorMessage(request))
				       .build();
	}
	
	private String extractSummary(ScoutingReport report) {
		if (StringUtils.hasText(report.getGeneratedReport())) {
			return report.getGeneratedReport();
		}
		return "Report generated at " + report.getCreatedAt();
	}
	
	private String getErrorMessage(ReportRequest request) {
		if (request.getStatus() == ReportRequest.ReportStatus.FAILED) {
			return StringUtils.hasText(request.getErrorMessage())
					       ? request.getErrorMessage()
					       : "Processing failed";
		}
		return null;
	}
	
	private String buildTitle(String reportType) {
		if (reportType == null || reportType.isEmpty()) {
			return "Scouting Report";
		}
		return "Scouting Report - " +
				       reportType.replace("_", " ").toUpperCase();
	}

	private List<ScoutingReportResponse.ReportSection> parseSections(String reportData) {
		if (!StringUtils.hasText(reportData)) {
			return Collections.emptyList();
		}
		List<ScoutingReportResponse.ReportSection> sections = new ArrayList<>();
		try {
			JsonNode root = objectMapper.readTree(reportData);
			if (root == null || !root.isObject()) {
				return Collections.emptyList();
			}
			AtomicInteger order = new AtomicInteger(1);
			root.fields().forEachRemaining(entry -> sections.add(
				ScoutingReportResponse.ReportSection
						.builder()
						.title(formatTitle(entry.getKey()))
						.content(entry.getValue().toString())
						.order(order.getAndIncrement())
						.build()
			));
			
		} catch (Exception e) {
			log.error("Failed to parse report data using regex", e);
		}
		return sections;
	}

	private String formatTitle(String key) {
		if (key == null || key.isEmpty()) return "";
		return StringUtils.capitalize(key.replace("_", " "));
	}

	private int calculateProgress(ReportRequest.ReportStatus status) {
		return switch (status) {
			case PENDING -> 0;
			case PROCESSING -> 50;
			case COMPLETED -> 100;
			case FAILED -> -1;
		};
	}
	
	private String getCurrentStep(ReportRequest.ReportStatus status) {
		return switch (status) {
			case PENDING -> "Queued for processing";
			case PROCESSING -> "Analyzing team data";
			case COMPLETED -> "Report ready";
			case FAILED -> "Processing failed";
		};
	}

	private void validateRequest(GenerateReportRequest request) {
		if (request == null || !StringUtils.hasText(request.getUserPrompt())) {
			throw new IllegalArgumentException("User prompt cannot be empty");
		}
		if (request.getUserPrompt().length() < 10) {
			throw new IllegalArgumentException("User prompt is too short (min 10 characters)");
		}
		if (request.getUserPrompt().length() > 500) {
			throw new IllegalArgumentException("User prompt is too long (max 500 characters)");
		}
	}
}
