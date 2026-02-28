package app.bola.cloud9stratigenai.dto;

import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ReportStatusResponse {
    private String requestId;
    private String error;
    private String status;
    private String message;
    private Integer progress; // 0-100
    private String currentStep;
    private LocalDateTime createdAt;
    private Boolean reportAvailable;
    private LocalDateTime completedAt;

    // Additive v1 hardening fields
    private String workflowState;
    private String errorCode;
    private Boolean retryable;
    private String contractVersion;
}
