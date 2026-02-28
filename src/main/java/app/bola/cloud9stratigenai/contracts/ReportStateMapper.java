package app.bola.cloud9stratigenai.contracts;

import app.bola.cloud9stratigenai.model.ReportRequest;

public final class ReportStateMapper {

    private ReportStateMapper() {
    }

    public static WorkflowState toWorkflowState(ReportRequest.ReportStatus status) {
        if (status == null) {
            return WorkflowState.QUEUED;
        }

        return switch (status) {
            case PENDING -> WorkflowState.QUEUED;
            case PROCESSING -> WorkflowState.INGESTING;
            case COMPLETED -> WorkflowState.READY;
            case FAILED -> WorkflowState.FAILED;
        };
    }
}
