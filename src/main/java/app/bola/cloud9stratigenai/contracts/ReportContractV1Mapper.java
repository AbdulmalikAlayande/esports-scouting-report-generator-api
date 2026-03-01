package app.bola.cloud9stratigenai.contracts;

import app.bola.cloud9stratigenai.dto.ScoutingReportResponse;
import app.bola.cloud9stratigenai.model.ReportArtifact;
import app.bola.cloud9stratigenai.model.ReportJob;
import app.bola.cloud9stratigenai.model.ReportRequest;
import app.bola.cloud9stratigenai.model.ScoutingReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportContractV1Mapper {

    private final ObjectMapper objectMapper;

    public ScoutingReportResponse fromArtifact(ReportRequest request, ReportArtifact artifact, ReportJob reportJob) {
        JsonNode reportRoot = parseReportRoot(artifact.getReportJson());

        return ScoutingReportResponse.builder()
                .requestId(request.getPublicId())
                .reportType(artifact.getReportType())
                .reportTitle(buildTitle(artifact.getReportType()))
                .summary(resolveArtifactSummary(artifact))
                .createdAt(artifact.getCreatedAt())
                .sections(parseSections(reportRoot))
                .contractVersion(resolveFrozenContractVersion(artifact.getContractVersion()))
                .modelVersion(resolveModelVersion(artifact.getModelVersion(), reportRoot))
                .featureVersion(resolveFeatureVersion(artifact.getFeatureVersion(), reportRoot))
                .generatedAt(artifact.getGeneratedAt())
                .lineage(buildLineage(request.getPublicId(), reportJob))
                .build();
    }

    public ScoutingReportResponse fromLegacy(ScoutingReport report, ReportJob reportJob) {
        JsonNode reportRoot = parseReportRoot(report.getReportData());
        String requestId = report.getReportRequest().getPublicId();

        return ScoutingReportResponse.builder()
                .requestId(requestId)
                .reportType(report.getReportType())
                .reportTitle(buildTitle(report.getReportType()))
                .summary(resolveLegacySummary(report))
                .createdAt(report.getCreatedAt())
                .sections(parseSections(reportRoot))
                .contractVersion(ContractVersions.SCOUTING_REPORT_V1)
                .modelVersion(resolveModelVersion(null, reportRoot))
                .featureVersion(resolveFeatureVersion(null, reportRoot))
                .generatedAt(report.getCreatedAt())
                .lineage(buildLineage(requestId, reportJob))
                .build();
    }

    private String resolveArtifactSummary(ReportArtifact artifact) {
        if (StringUtils.hasText(artifact.getSummary())) {
            return artifact.getSummary();
        }
        return "Report generated at " + artifact.getGeneratedAt();
    }

    private String resolveLegacySummary(ScoutingReport report) {
        if (StringUtils.hasText(report.getGeneratedReport())) {
            return report.getGeneratedReport();
        }
        return "Report generated at " + report.getCreatedAt();
    }

    private String resolveFrozenContractVersion(String workerContractVersion) {
        if (!StringUtils.hasText(workerContractVersion)) {
            return ContractVersions.SCOUTING_REPORT_V1;
        }

        if (!ContractVersions.SCOUTING_REPORT_V1.equals(workerContractVersion)) {
            log.warn("Unsupported report contract version from worker payload: {}. Forcing {}.",
                    workerContractVersion,
                    ContractVersions.SCOUTING_REPORT_V1);
        }

        return ContractVersions.SCOUTING_REPORT_V1;
    }

    private String resolveModelVersion(String modelVersion, JsonNode reportRoot) {
        if (StringUtils.hasText(modelVersion)) {
            return modelVersion;
        }
        return readMetadataField(reportRoot, "model_version", "legacy-v0");
    }

    private String resolveFeatureVersion(String featureVersion, JsonNode reportRoot) {
        if (StringUtils.hasText(featureVersion)) {
            return featureVersion;
        }
        return readMetadataField(reportRoot, "feature_version", "legacy-v0");
    }

    private ScoutingReportResponse.Lineage buildLineage(String requestId, ReportJob reportJob) {
        return ScoutingReportResponse.Lineage.builder()
                .requestId(requestId)
                .jobId(reportJob != null ? reportJob.getId() : null)
                .attempt(reportJob != null ? reportJob.getAttempt() : 1)
                .build();
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

    private String buildTitle(String reportType) {
        if (!StringUtils.hasText(reportType)) {
            return "Scouting Report";
        }
        return "Scouting Report - " + reportType.replace("_", " ").toUpperCase();
    }

    private String formatTitle(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return StringUtils.capitalize(key.replace("_", " "));
    }
}
