package app.bola.cloud9stratigenai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoutingReportResponse {

    private String requestId;
    private String reportType;
    private String reportTitle;
    private String summary;
    private LocalDateTime createdAt;
    private List<ReportSection> sections;

    // Additive v1 hardening fields
    private String contractVersion;
    private String modelVersion;
    private String featureVersion;
    private LocalDateTime generatedAt;
    private Lineage lineage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportSection {
        private String title;
        private Object content;
        private Integer order;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Lineage {
        private String requestId;
        private Long jobId;
        private Integer attempt;
    }
}
