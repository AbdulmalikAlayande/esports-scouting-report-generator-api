package app.bola.cloud9stratigenai.service;

import app.bola.cloud9stratigenai.contracts.ContractVersions;
import app.bola.cloud9stratigenai.contracts.ErrorCode;
import app.bola.cloud9stratigenai.contracts.ReportStateMapper;
import app.bola.cloud9stratigenai.contracts.WorkflowState;
import app.bola.cloud9stratigenai.dto.GenerateReportRequest;
import app.bola.cloud9stratigenai.dto.ReportStatusResponse;
import app.bola.cloud9stratigenai.dto.ScoutingReportResponse;
import app.bola.cloud9stratigenai.exception.ReportNotFoundException;
import app.bola.cloud9stratigenai.exception.ReportNotReadyException;
import app.bola.cloud9stratigenai.model.ReportArtifact;
import app.bola.cloud9stratigenai.model.ReportJob;
import app.bola.cloud9stratigenai.model.ReportRequest;
import app.bola.cloud9stratigenai.model.ScoutingReport;
import app.bola.cloud9stratigenai.repository.ReportArtifactRepository;
import app.bola.cloud9stratigenai.repository.ReportJobRepository;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@AllArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final ModelMapper mapper;
    private final ObjectMapper objectMapper;
    private final ReportRequestRepository reportRequestRepository;
    private final ScoutingReportRepository scoutingReportRepository;
    private final ReportArtifactRepository reportArtifactRepository;
    private final ReportJobRepository reportJobRepository;

    @Override
    @Transactional
    public ReportStatusResponse generateReport(GenerateReportRequest request) {
        validateRequest(request);

        String requestHash = hashPrompt(request.getUserPrompt());
        Optional<ReportRequest> existing = reportRequestRepository.findFirstByRequestHashAndStatusInOrderByCreatedAtDesc(
                requestHash,
                List.of(ReportRequest.ReportStatus.PENDING, ReportRequest.ReportStatus.PROCESSING, ReportRequest.ReportStatus.COMPLETED)
        );

        if (existing.isPresent()) {
            ReportRequest existingRequest = existing.get();
            boolean reportAvailable = isReportAvailable(existingRequest.getPublicId());
            return buildReportStatusResponse(existingRequest, reportAvailable, reportJobRepository.findByReportRequestId(existingRequest.getId()).orElse(null));
        }

        ReportRequest reportRequest = mapper.map(request, ReportRequest.class);
        reportRequest.setStatus(ReportRequest.ReportStatus.PENDING);
        reportRequest.setRequestHash(requestHash);

        ReportRequest savedRequest = reportRequestRepository.save(reportRequest);

        ReportJob reportJob = new ReportJob();
        reportJob.setReportRequest(savedRequest);
        reportJob.setState(ReportJob.JobState.QUEUED);
        reportJob.setCurrentStage(ReportJob.JobStage.INGESTING);
        reportJob.setAttempt(0);
        reportJob.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        reportJob.setNextRunAt(LocalDateTime.now());
        reportJobRepository.save(reportJob);

        return buildReportStatusResponse(savedRequest, false, reportJob);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportStatusResponse getReportStatus(String requestId) {
        ReportRequest request = findRequestOrThrow(requestId);
        boolean isAvailable = isReportAvailable(requestId);
        ReportJob reportJob = reportJobRepository.findByReportRequestId(request.getId()).orElse(null);
        return buildReportStatusResponse(request, isAvailable, reportJob);
    }

    @Override
    @Transactional(readOnly = true)
    public ScoutingReportResponse getReport(String requestId) {
        ReportRequest request = findRequestOrThrow(requestId);
        if (request.getStatus() != ReportRequest.ReportStatus.COMPLETED) {
            throw new ReportNotReadyException(requestId);
        }

        ReportJob reportJob = reportJobRepository.findByReportRequestId(request.getId()).orElse(null);

        Optional<ReportArtifact> reportArtifact = reportArtifactRepository.findByReportRequestId(request.getId());
        if (reportArtifact.isPresent()) {
            return mapArtifactToResponse(request, reportArtifact.get(), reportJob);
        }

        ScoutingReport scoutingReport = scoutingReportRepository.findByReportRequestId(request.getId())
                .orElseThrow(() -> new ReportNotFoundException("Scouting report data not found for request: " + requestId));
        return mapLegacyReportToResponse(scoutingReport, reportJob);
    }

    private ReportRequest findRequestOrThrow(String publicId) {
        return reportRequestRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ReportNotFoundException(publicId));
    }

    private ScoutingReportResponse mapArtifactToResponse(ReportRequest request, ReportArtifact artifact, ReportJob reportJob) {
        JsonNode reportRoot = parseReportRoot(artifact.getReportJson());

        return ScoutingReportResponse.builder()
                .requestId(request.getPublicId())
                .reportType(artifact.getReportType())
                .reportTitle(buildTitle(artifact.getReportType()))
                .summary(StringUtils.hasText(artifact.getSummary()) ? artifact.getSummary() : "Report generated at " + artifact.getGeneratedAt())
                .createdAt(artifact.getCreatedAt())
                .sections(parseSections(reportRoot))
                .contractVersion(StringUtils.hasText(artifact.getContractVersion()) ? artifact.getContractVersion() : ContractVersions.SCOUTING_REPORT_V1)
                .modelVersion(StringUtils.hasText(artifact.getModelVersion()) ? artifact.getModelVersion() : readMetadataField(reportRoot, "model_version", "legacy-v0"))
                .featureVersion(StringUtils.hasText(artifact.getFeatureVersion()) ? artifact.getFeatureVersion() : readMetadataField(reportRoot, "feature_version", "legacy-v0"))
                .generatedAt(artifact.getGeneratedAt())
                .lineage(ScoutingReportResponse.Lineage.builder()
                        .requestId(request.getPublicId())
                        .jobId(reportJob != null ? reportJob.getId() : null)
                        .attempt(reportJob != null ? reportJob.getAttempt() : 1)
                        .build())
                .build();
    }

    private ScoutingReportResponse mapLegacyReportToResponse(ScoutingReport report, ReportJob reportJob) {
        JsonNode reportRoot = parseReportRoot(report.getReportData());

        return ScoutingReportResponse.builder()
                .requestId(report.getReportRequest().getPublicId())
                .reportType(report.getReportType())
                .reportTitle(buildTitle(report.getReportType()))
                .summary(extractSummary(report))
                .createdAt(report.getCreatedAt())
                .sections(parseSections(reportRoot))
                .contractVersion(ContractVersions.SCOUTING_REPORT_V1)
                .modelVersion(readMetadataField(reportRoot, "model_version", "legacy-v0"))
                .featureVersion(readMetadataField(reportRoot, "feature_version", "legacy-v0"))
                .generatedAt(report.getCreatedAt())
                .lineage(ScoutingReportResponse.Lineage.builder()
                        .requestId(report.getReportRequest().getPublicId())
                        .jobId(reportJob != null ? reportJob.getId() : null)
                        .attempt(reportJob != null ? reportJob.getAttempt() : 1)
                        .build())
                .build();
    }

    private ReportStatusResponse buildReportStatusResponse(ReportRequest request, boolean reportAvailable, ReportJob reportJob) {
        WorkflowState workflowState = resolveWorkflowState(request, reportJob);
        ErrorClassification classification = classifyError(request, reportJob);

        return ReportStatusResponse.builder()
                .requestId(request.getPublicId())
                .status(request.getStatus().name())
                .message(resolveCurrentStep(request.getStatus(), reportJob))
                .progress(resolveProgress(request.getStatus(), reportJob))
                .currentStep(resolveCurrentStep(request.getStatus(), reportJob))
                .createdAt(request.getCreatedAt())
                .reportAvailable(reportAvailable)
                .completedAt(request.getCompletedAt())
                .error(getErrorMessage(request))
                .workflowState(workflowState.name())
                .errorCode(classification.errorCode != null ? classification.errorCode.name() : null)
                .retryable(classification.retryable)
                .contractVersion(ContractVersions.REPORT_STATUS_V1)
                .build();
    }

    private WorkflowState resolveWorkflowState(ReportRequest request, ReportJob reportJob) {
        if (reportJob != null && reportJob.getCurrentStage() != null) {
            return switch (reportJob.getCurrentStage()) {
                case INGESTING -> WorkflowState.INGESTING;
                case FEATURIZING -> WorkflowState.FEATURIZING;
                case SYNTHESIZING -> WorkflowState.SYNTHESIZING;
                case COMPOSING -> WorkflowState.COMPOSING;
                case READY -> WorkflowState.READY;
                case FAILED -> WorkflowState.FAILED;
            };
        }

        return ReportStateMapper.toWorkflowState(request.getStatus());
    }

    private int resolveProgress(ReportRequest.ReportStatus status, ReportJob reportJob) {
        if (reportJob != null && reportJob.getCurrentStage() != null) {
            return switch (reportJob.getCurrentStage()) {
                case INGESTING -> 20;
                case FEATURIZING -> 40;
                case SYNTHESIZING -> 70;
                case COMPOSING -> 90;
                case READY -> 100;
                case FAILED -> -1;
            };
        }

        return switch (status) {
            case PENDING -> 0;
            case PROCESSING -> 50;
            case COMPLETED -> 100;
            case FAILED -> -1;
        };
    }

    private String resolveCurrentStep(ReportRequest.ReportStatus status, ReportJob reportJob) {
        if (reportJob != null && reportJob.getCurrentStage() != null) {
            return switch (reportJob.getCurrentStage()) {
                case INGESTING -> "Ingesting source data";
                case FEATURIZING -> "Computing tactical features";
                case SYNTHESIZING -> "Synthesizing insights";
                case COMPOSING -> "Composing final report";
                case READY -> "Report ready";
                case FAILED -> "Processing failed";
            };
        }

        return switch (status) {
            case PENDING -> "Queued for processing";
            case PROCESSING -> "Analyzing team data";
            case COMPLETED -> "Report ready";
            case FAILED -> "Processing failed";
        };
    }

    private boolean isReportAvailable(String requestId) {
        return reportArtifactRepository.existsByReportRequestPublicId(requestId)
                || scoutingReportRepository.existsByReportRequestPublicId(requestId);
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

    private JsonNode parseReportRoot(String reportData) {
        if (!StringUtils.hasText(reportData)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(reportData);
            if (root == null || !root.isObject()) {
                return null;
            }
            return root;
        } catch (Exception e) {
            log.error("Failed to parse report data", e);
            return null;
        }
    }

    private List<ScoutingReportResponse.ReportSection> parseSections(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Collections.emptyList();
        }

        List<ScoutingReportResponse.ReportSection> sections = new ArrayList<>();
        AtomicInteger order = new AtomicInteger(1);
        root.fields().forEachRemaining(entry -> {
            if ("metadata".equals(entry.getKey())) {
                return;
            }
            sections.add(
                    ScoutingReportResponse.ReportSection
                            .builder()
                            .title(formatTitle(entry.getKey()))
                            .content(entry.getValue().toString())
                            .order(order.getAndIncrement())
                            .build()
            );
        });

        return sections;
    }

    private String readMetadataField(JsonNode root, String key, String fallback) {
        if (root == null) {
            return fallback;
        }

        JsonNode metadata = root.path("metadata");
        if (metadata.isMissingNode() || !metadata.isObject()) {
            return fallback;
        }

        JsonNode value = metadata.path(key);
        if (value.isTextual() && StringUtils.hasText(value.asText())) {
            return value.asText();
        }

        return fallback;
    }

    private String formatTitle(String key) {
        if (key == null || key.isEmpty()) return "";
        return StringUtils.capitalize(key.replace("_", " ".trim()));
    }

    private ErrorClassification classifyError(ReportRequest request, ReportJob reportJob) {
        if (request.getStatus() != ReportRequest.ReportStatus.FAILED) {
            return new ErrorClassification(null, null);
        }

        if (reportJob != null && StringUtils.hasText(reportJob.getLastErrorCode())) {
            ErrorCode mapped = safeParseErrorCode(reportJob.getLastErrorCode());
            return new ErrorClassification(mapped, reportJob.getRetryable());
        }

        return classifyErrorFromMessage(request.getErrorMessage());
    }

    private ErrorClassification classifyErrorFromMessage(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return new ErrorClassification(ErrorCode.NON_RETRYABLE_DATA, false);
        }

        String normalized = errorMessage.toLowerCase(Locale.ROOT);

        if (normalized.contains("timeout") || normalized.contains("timed out") || normalized.contains("rate limit")
                || normalized.contains("unavailable") || normalized.contains("connection reset")) {
            return new ErrorClassification(ErrorCode.RETRYABLE_PROVIDER, true);
        }

        if (normalized.contains("database") || normalized.contains("deadlock") || normalized.contains("connection refused")
                || normalized.contains("connection pool")) {
            return new ErrorClassification(ErrorCode.RETRYABLE_INFRA, true);
        }

        if (normalized.contains("validation") || normalized.contains("fieldundefined") || normalized.contains("schema")
                || normalized.contains("contract")) {
            return new ErrorClassification(ErrorCode.NON_RETRYABLE_CONTRACT, false);
        }

        if (normalized.contains("unauthorized") || normalized.contains("forbidden") || normalized.contains("auth")) {
            return new ErrorClassification(ErrorCode.NON_RETRYABLE_AUTH, false);
        }

        if (normalized.contains("config") || normalized.contains("environment")) {
            return new ErrorClassification(ErrorCode.NON_RETRYABLE_CONFIG, false);
        }

        return new ErrorClassification(ErrorCode.NON_RETRYABLE_DATA, false);
    }

    private ErrorCode safeParseErrorCode(String raw) {
        try {
            return ErrorCode.valueOf(raw);
        } catch (Exception ignored) {
            return ErrorCode.UNKNOWN;
        }
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

    private String hashPrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 hashing unavailable", e);
        }
    }

    private static final class ErrorClassification {
        private final ErrorCode errorCode;
        private final Boolean retryable;

        private ErrorClassification(ErrorCode errorCode, Boolean retryable) {
            this.errorCode = errorCode;
            this.retryable = retryable;
        }
    }
}

