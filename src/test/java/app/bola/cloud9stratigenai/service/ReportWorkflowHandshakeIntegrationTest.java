package app.bola.cloud9stratigenai.service;

import app.bola.cloud9stratigenai.common.model.BaseModel;
import app.bola.cloud9stratigenai.contracts.ContractVersions;
import app.bola.cloud9stratigenai.contracts.ReportContractV1Mapper;
import app.bola.cloud9stratigenai.dto.GenerateReportRequest;
import app.bola.cloud9stratigenai.dto.ReportStatusResponse;
import app.bola.cloud9stratigenai.dto.ScoutingReportResponse;
import app.bola.cloud9stratigenai.model.ReportArtifact;
import app.bola.cloud9stratigenai.model.ReportJob;
import app.bola.cloud9stratigenai.model.ReportRequest;
import app.bola.cloud9stratigenai.repository.ReportArtifactRepository;
import app.bola.cloud9stratigenai.repository.ReportJobRepository;
import app.bola.cloud9stratigenai.repository.ReportRequestRepository;
import app.bola.cloud9stratigenai.repository.ScoutingReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportWorkflowHandshakeIntegrationTest {

    @Mock
    private ReportRequestRepository reportRequestRepository;
    @Mock
    private ScoutingReportRepository scoutingReportRepository;
    @Mock
    private ReportArtifactRepository reportArtifactRepository;
    @Mock
    private ReportJobRepository reportJobRepository;
    @Mock
    private ModelMapper mapper;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        reportService = new ReportServiceImpl(
                mapper,
                reportRequestRepository,
                scoutingReportRepository,
                reportArtifactRepository,
                reportJobRepository,
                new ReportContractV1Mapper(objectMapper)
        );

        lenient().when(reportArtifactRepository.findByReportRequestId(any(Long.class))).thenReturn(Optional.empty());
        lenient().when(reportArtifactRepository.existsByReportRequestPublicId(any())).thenReturn(false);
    }

    @Test
    @DisplayName("Should traverse QUEUED->INGESTING->FEATURIZING->SYNTHESIZING->COMPOSING->READY and return frozen ReportContract v1")
    void shouldTraverseWorkflowToReadyAndReturnFrozenContract() throws Exception {
        String requestId = "workflow-e2e-uuid";
        GenerateReportRequest request = new GenerateReportRequest("Scout Sentinels setup tendencies on Lotus and Ascent");

        ReportRequest persistedRequest = new ReportRequest();
        setBaseModel(persistedRequest, 101L, requestId);
        persistedRequest.setCreatedAt(LocalDateTime.now());
        persistedRequest.setStatus(ReportRequest.ReportStatus.PENDING);

        ReportJob persistedJob = new ReportJob();
        persistedJob.setId(901L);
        persistedJob.setReportRequest(persistedRequest);
        persistedJob.setState(ReportJob.JobState.QUEUED);
        persistedJob.setCurrentStage(ReportJob.JobStage.INGESTING);
        persistedJob.setAttempt(0);
        persistedJob.setMaxAttempts(5);

        ReportRequest mappedRequest = new ReportRequest();
        mappedRequest.setUserPrompt(request.getUserPrompt());

        AtomicBoolean artifactAvailable = new AtomicBoolean(false);

        when(reportRequestRepository.findFirstByRequestHashAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(mapper.map(request, ReportRequest.class)).thenReturn(mappedRequest);
        when(reportRequestRepository.save(any(ReportRequest.class))).thenAnswer(invocation -> {
            ReportRequest incoming = invocation.getArgument(0);
            persistedRequest.setStatus(incoming.getStatus());
            persistedRequest.setRequestHash(incoming.getRequestHash());
            return persistedRequest;
        });
        when(reportJobRepository.save(any(ReportJob.class))).thenAnswer(invocation -> {
            ReportJob incoming = invocation.getArgument(0);
            persistedJob.setCurrentStage(incoming.getCurrentStage());
            persistedJob.setState(incoming.getState());
            persistedJob.setAttempt(incoming.getAttempt());
            persistedJob.setNextRunAt(incoming.getNextRunAt());
            return persistedJob;
        });

        when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(persistedRequest));
        when(reportJobRepository.findByReportRequestId(101L)).thenAnswer(invocation -> Optional.of(persistedJob));
        when(reportArtifactRepository.existsByReportRequestPublicId(requestId)).thenAnswer(invocation -> artifactAvailable.get());
        when(scoutingReportRepository.existsByReportRequestPublicId(requestId)).thenReturn(false);

        ReportStatusResponse queued = reportService.generateReport(request);
        assertEquals("PENDING", queued.getStatus());
        assertEquals("INGESTING", queued.getWorkflowState());
        assertEquals(20, queued.getProgress());
        assertEquals(ContractVersions.REPORT_STATUS_V1, queued.getContractVersion());

        persistedRequest.setStatus(ReportRequest.ReportStatus.PROCESSING);
        persistedJob.setState(ReportJob.JobState.RUNNING);

        persistedJob.setCurrentStage(ReportJob.JobStage.INGESTING);
        assertWorkflow(reportService.getReportStatus(requestId), "INGESTING", 20, false);

        persistedJob.setCurrentStage(ReportJob.JobStage.FEATURIZING);
        assertWorkflow(reportService.getReportStatus(requestId), "FEATURIZING", 40, false);

        persistedJob.setCurrentStage(ReportJob.JobStage.SYNTHESIZING);
        assertWorkflow(reportService.getReportStatus(requestId), "SYNTHESIZING", 70, false);

        persistedJob.setCurrentStage(ReportJob.JobStage.COMPOSING);
        assertWorkflow(reportService.getReportStatus(requestId), "COMPOSING", 90, false);

        persistedRequest.setStatus(ReportRequest.ReportStatus.COMPLETED);
        persistedRequest.setCompletedAt(LocalDateTime.now());
        persistedJob.setCurrentStage(ReportJob.JobStage.READY);
        persistedJob.setState(ReportJob.JobState.COMPLETED);
        persistedJob.setAttempt(2);
        artifactAvailable.set(true);

        ReportStatusResponse ready = reportService.getReportStatus(requestId);
        assertWorkflow(ready, "READY", 100, true);

        ReportArtifact artifact = new ReportArtifact();
        artifact.setReportRequest(persistedRequest);
        artifact.setReportType("full");
        artifact.setContractVersion("scouting-report.v99");
        artifact.setSummary("Final composed report from worker");
        artifact.setReportJson("{" +
                "\"summary\": \"Team overview\"," +
                "\"metadata\": {\"model_version\": \"gemini-3-flash\", \"feature_version\": \"features-v4\"}" +
                "}");
        artifact.setGeneratedAt(LocalDateTime.now());
        artifact.setCreatedAt(LocalDateTime.now());

        when(reportArtifactRepository.findByReportRequestId(101L)).thenReturn(Optional.of(artifact));

        ScoutingReportResponse reportResponse = reportService.getReport(requestId);

        assertNotNull(reportResponse);
        assertEquals(ContractVersions.SCOUTING_REPORT_V1, reportResponse.getContractVersion());
        assertEquals("gemini-3-flash", reportResponse.getModelVersion());
        assertEquals("features-v4", reportResponse.getFeatureVersion());
        assertNotNull(reportResponse.getLineage());
        assertEquals(2, reportResponse.getLineage().getAttempt());
    }

    @Test
    @DisplayName("Should expose retryable failure, retry transition, and terminal non-retryable failure in status contract")
    void shouldExposeRetryPathAndTerminalFailure() throws Exception {
        String requestId = "workflow-retry-uuid";

        ReportRequest request = new ReportRequest();
        setBaseModel(request, 202L, requestId);
        request.setStatus(ReportRequest.ReportStatus.PROCESSING);
        request.setCreatedAt(LocalDateTime.now());

        ReportJob job = new ReportJob();
        job.setId(902L);
        job.setReportRequest(request);
        job.setState(ReportJob.JobState.RUNNING);
        job.setCurrentStage(ReportJob.JobStage.INGESTING);
        job.setAttempt(1);

        when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));
        when(reportJobRepository.findByReportRequestId(202L)).thenReturn(Optional.of(job));
        when(reportArtifactRepository.existsByReportRequestPublicId(requestId)).thenReturn(false);
        when(scoutingReportRepository.existsByReportRequestPublicId(requestId)).thenReturn(false);

        request.setStatus(ReportRequest.ReportStatus.FAILED);
        request.setErrorMessage("Provider timeout from GRID");
        job.setCurrentStage(ReportJob.JobStage.FAILED);
        job.setState(ReportJob.JobState.FAILED);
        job.setLastErrorCode("RETRYABLE_PROVIDER");
        job.setRetryable(true);

        ReportStatusResponse retryableFailure = reportService.getReportStatus(requestId);
        assertEquals("FAILED", retryableFailure.getWorkflowState());
        assertEquals("RETRYABLE_PROVIDER", retryableFailure.getErrorCode());
        assertTrue(Boolean.TRUE.equals(retryableFailure.getRetryable()));

        request.setStatus(ReportRequest.ReportStatus.PROCESSING);
        request.setErrorMessage(null);
        job.setState(ReportJob.JobState.QUEUED);
        job.setCurrentStage(ReportJob.JobStage.INGESTING);
        job.setAttempt(2);
        job.setLastErrorCode(null);
        job.setRetryable(null);

        ReportStatusResponse retrying = reportService.getReportStatus(requestId);
        assertEquals("INGESTING", retrying.getWorkflowState());
        assertEquals(20, retrying.getProgress());
        assertNull(retrying.getErrorCode());
        assertNull(retrying.getRetryable());

        request.setStatus(ReportRequest.ReportStatus.FAILED);
        request.setErrorMessage("Schema validation failed");
        job.setState(ReportJob.JobState.FAILED);
        job.setCurrentStage(ReportJob.JobStage.FAILED);
        job.setAttempt(5);
        job.setLastErrorCode("NON_RETRYABLE_CONTRACT");
        job.setRetryable(false);

        ReportStatusResponse terminalFailure = reportService.getReportStatus(requestId);
        assertEquals("FAILED", terminalFailure.getWorkflowState());
        assertEquals("NON_RETRYABLE_CONTRACT", terminalFailure.getErrorCode());
        assertTrue(Boolean.FALSE.equals(terminalFailure.getRetryable()));
        assertEquals("Processing failed", terminalFailure.getCurrentStep());
    }

    private void assertWorkflow(ReportStatusResponse response, String workflowState, int progress, boolean reportAvailable) {
        assertEquals(workflowState, response.getWorkflowState());
        assertEquals(progress, response.getProgress());
        assertEquals(reportAvailable, Boolean.TRUE.equals(response.getReportAvailable()));
        assertEquals(ContractVersions.REPORT_STATUS_V1, response.getContractVersion());
    }

    private static void setBaseModel(ReportRequest request, Long id, String publicId) throws Exception {
        Field idField = BaseModel.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(request, id);

        Field publicIdField = BaseModel.class.getDeclaredField("publicId");
        publicIdField.setAccessible(true);
        publicIdField.set(request, publicId);
    }
}
