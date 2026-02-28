package app.bola.cloud9stratigenai.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "report_jobs")
public class ReportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_request_id", nullable = false, unique = true)
    private ReportRequest reportRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private JobState state = JobState.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false)
    private JobStage currentStage = JobStage.INGESTING;

    @Column(name = "attempt", nullable = false)
    private Integer attempt = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 5;

    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt = LocalDateTime.now();

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "retryable")
    private Boolean retryable;

    public enum JobState {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public enum JobStage {
        INGESTING,
        FEATURIZING,
        SYNTHESIZING,
        COMPOSING,
        READY,
        FAILED
    }
}
