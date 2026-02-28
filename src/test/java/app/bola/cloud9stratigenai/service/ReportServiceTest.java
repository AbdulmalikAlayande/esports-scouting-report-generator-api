package app.bola.cloud9stratigenai.service;

import app.bola.cloud9stratigenai.contracts.ContractVersions;
import app.bola.cloud9stratigenai.dto.GenerateReportRequest;
import app.bola.cloud9stratigenai.dto.ReportStatusResponse;
import app.bola.cloud9stratigenai.dto.ScoutingReportResponse;
import app.bola.cloud9stratigenai.exception.ReportNotFoundException;
import app.bola.cloud9stratigenai.exception.ReportNotReadyException;
import app.bola.cloud9stratigenai.model.ReportJob;
import app.bola.cloud9stratigenai.model.ReportRequest;
import app.bola.cloud9stratigenai.model.ScoutingReport;
import app.bola.cloud9stratigenai.repository.ReportJobRepository;
import app.bola.cloud9stratigenai.repository.ReportRequestRepository;
import app.bola.cloud9stratigenai.repository.ScoutingReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportRequestRepository reportRequestRepository;
    @Mock
    private ScoutingReportRepository scoutingReportRepository;
    @Mock
    private ReportJobRepository reportJobRepository;
    @Mock
    private ModelMapper mapper;

    private ObjectMapper objectMapper;
    private ReportService reportService;
    private Validator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        reportService = new ReportServiceImpl(
                mapper,
                objectMapper,
                reportRequestRepository,
                scoutingReportRepository,
                reportJobRepository
        );
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("Use Case 1: Submit Report Request")
    class SubmitReportRequestTests {

        @Test
        @DisplayName("Should create queued request and report job for valid prompt")
        void shouldCreatePendingRequest_WhenPromptIsValid() throws Exception {
            String validPrompt = "This is a valid scouting report request prompt with enough length.";
            GenerateReportRequest request = new GenerateReportRequest(validPrompt);

            when(reportRequestRepository.findFirstByRequestHashAndStatusInOrderByCreatedAtDesc(any(), any()))
                    .thenReturn(Optional.empty());

            ReportRequest reportRequest = new ReportRequest();
            reportRequest.setUserPrompt(validPrompt);
            when(mapper.map(request, ReportRequest.class)).thenReturn(reportRequest);

            when(reportRequestRepository.save(any(ReportRequest.class))).thenAnswer(invocation -> {
                ReportRequest r = invocation.getArgument(0);
                Field publicId = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
                publicId.setAccessible(true);
                publicId.set(r, "test-uuid-123");
                return r;
            });

            when(reportJobRepository.save(any(ReportJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ReportStatusResponse response = reportService.generateReport(request);

            assertNotNull(response);
            assertEquals("test-uuid-123", response.getRequestId());
            assertEquals("PENDING", response.getStatus());
            assertEquals("INGESTING", response.getWorkflowState());
            assertEquals(20, response.getProgress());
            assertEquals(ContractVersions.REPORT_STATUS_V1, response.getContractVersion());

            verify(reportRequestRepository).save(any(ReportRequest.class));
            verify(reportJobRepository).save(any(ReportJob.class));
        }

        @Test
        @DisplayName("Should return existing request for idempotent prompt")
        void shouldReuseExistingRequestForIdempotentPrompt() throws Exception {
            String prompt = "Scout Team Liquid over last 3 months";
            GenerateReportRequest request = new GenerateReportRequest(prompt);

            ReportRequest existing = new ReportRequest();
            existing.setStatus(ReportRequest.ReportStatus.PENDING);

            Field publicId = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
            publicId.setAccessible(true);
            publicId.set(existing, "existing-uuid");

            Field idField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(existing, 77L);

            when(reportRequestRepository.findFirstByRequestHashAndStatusInOrderByCreatedAtDesc(any(), any()))
                    .thenReturn(Optional.of(existing));
            when(scoutingReportRepository.existsByReportRequestPublicId("existing-uuid")).thenReturn(false);
            when(reportJobRepository.findByReportRequestId(77L)).thenReturn(Optional.empty());

            ReportStatusResponse response = reportService.generateReport(request);

            assertNotNull(response);
            assertEquals("existing-uuid", response.getRequestId());
            verify(reportRequestRepository, never()).save(any(ReportRequest.class));
            verify(reportJobRepository, never()).save(any(ReportJob.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "",
                "Too short",
                "A very long prompt that exceeds the maximum allowed limit of five hundred characters for a scouting report generation request. To reach this limit, I need to keep writing more and more words that don't necessarily have to make sense but should be enough to overflow the buffer or the validation logic. We are testing the boundary conditions of the application to ensure it remains stable even when users provide excessively long inputs. This is part of the aggressive testing strategy to cover all use cases. Still not enough? Let's add some more text here just in case."
        })
        @DisplayName("Should throw exception when prompt is invalid")
        void shouldThrowException_WhenPromptIsInvalid(String invalidPrompt) {
            GenerateReportRequest request = new GenerateReportRequest(invalidPrompt);

            var violations = validator.validate(request);
            assertFalse(violations.isEmpty(), "Validator should find violations for: " + invalidPrompt);

            assertThrows(IllegalArgumentException.class, () -> reportService.generateReport(request));
            verify(reportRequestRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Use Case 2: Status Contract Compatibility")
    class StatusContractCompatibilityTests {

        @Test
        @DisplayName("Should classify retryable provider errors while preserving legacy fields")
        void shouldClassifyRetryableProviderError() throws Exception {
            String requestId = "failed-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.FAILED);
            request.setErrorMessage("GRID upstream timed out while querying team stats");
            request.setCompletedAt(LocalDateTime.now());

            Field publicIdField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
            publicIdField.setAccessible(true);
            publicIdField.set(request, requestId);

            Field idField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, 10L);

            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));
            when(scoutingReportRepository.existsByReportRequestPublicId(requestId)).thenReturn(false);
            when(reportJobRepository.findByReportRequestId(10L)).thenReturn(Optional.empty());

            ReportStatusResponse response = reportService.getReportStatus(requestId);

            assertEquals("FAILED", response.getStatus());
            assertEquals("FAILED", response.getWorkflowState());
            assertEquals("RETRYABLE_PROVIDER", response.getErrorCode());
            assertTrue(Boolean.TRUE.equals(response.getRetryable()));
            assertEquals(-1, response.getProgress());
            assertEquals(ContractVersions.REPORT_STATUS_V1, response.getContractVersion());
            assertEquals("Processing failed", response.getCurrentStep());
        }

        @Test
        @DisplayName("Should prefer job-stage workflow state when report job exists")
        void shouldPreferJobStageForWorkflowState() throws Exception {
            String requestId = "processing-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.PROCESSING);

            Field publicId = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
            publicId.setAccessible(true);
            publicId.set(request, requestId);

            Field idField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, 11L);

            ReportJob job = new ReportJob();
            job.setCurrentStage(ReportJob.JobStage.SYNTHESIZING);
            job.setState(ReportJob.JobState.RUNNING);
            job.setAttempt(2);

            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));
            when(scoutingReportRepository.existsByReportRequestPublicId(requestId)).thenReturn(false);
            when(reportJobRepository.findByReportRequestId(11L)).thenReturn(Optional.of(job));

            ReportStatusResponse response = reportService.getReportStatus(requestId);

            assertEquals("SYNTHESIZING", response.getWorkflowState());
            assertEquals(70, response.getProgress());
        }
    }

    @Nested
    @DisplayName("Use Case 3: View Completed Report")
    class ViewCompletedReportTests {

        @Test
        @DisplayName("Should return structured report when status is COMPLETED")
        void shouldReturnStructuredReport_WhenCompleted() throws Exception {
            String requestId = "completed-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.COMPLETED);

            Field publicIdField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
            publicIdField.setAccessible(true);
            publicIdField.set(request, requestId);

            Field idField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, 1L);

            String reportJson = "{\"executive_summary\": \"This is a great team.\", \"tactical_analysis\": \"Use more smokes.\", \"metadata\": {\"model_version\": \"gemini-3-flash\", \"feature_version\": \"features-v1\"}}";
            ScoutingReport report = new ScoutingReport();
            report.setReportRequest(request);
            report.setReportType("VALORANT_PRO");
            report.setReportData(reportJson);
            report.setGeneratedReport("Full text summary of the report.");

            ReportJob reportJob = new ReportJob();
            reportJob.setId(123L);
            reportJob.setAttempt(3);

            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));
            when(scoutingReportRepository.findByReportRequestId(1L)).thenReturn(Optional.of(report));
            when(reportJobRepository.findByReportRequestId(1L)).thenReturn(Optional.of(reportJob));

            ScoutingReportResponse response = reportService.getReport(requestId);

            assertNotNull(response);
            assertEquals(requestId, response.getRequestId());
            assertEquals("VALORANT_PRO", response.getReportType());
            assertEquals("Full text summary of the report.", response.getSummary());
            assertEquals(2, response.getSections().size());
            assertEquals(ContractVersions.SCOUTING_REPORT_V1, response.getContractVersion());
            assertEquals("gemini-3-flash", response.getModelVersion());
            assertEquals("features-v1", response.getFeatureVersion());
            assertNotNull(response.getLineage());
            assertEquals(requestId, response.getLineage().getRequestId());
            assertEquals(123L, response.getLineage().getJobId());
            assertEquals(3, response.getLineage().getAttempt());
        }

        @Test
        @DisplayName("Should throw ReportNotReadyException when status is PENDING")
        void shouldThrowNotReady_WhenPending() {
            String requestId = "pending-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.PENDING);

            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));

            assertThrows(ReportNotReadyException.class, () -> reportService.getReport(requestId));
        }

        @Test
        @DisplayName("Should throw ReportNotFoundException when request does not exist")
        void shouldThrowNotFound_WhenRequestMissing() {
            String requestId = "missing-uuid";
            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.empty());

            assertThrows(ReportNotFoundException.class, () -> reportService.getReport(requestId));
        }

        @Test
        @DisplayName("Should handle invalid JSON in report data gracefully")
        void shouldHandleInvalidJson() throws Exception {
            String requestId = "bad-json-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.COMPLETED);

            Field publicIdField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
            publicIdField.setAccessible(true);
            publicIdField.set(request, requestId);

            Field idField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, 1L);

            ScoutingReport report = new ScoutingReport();
            report.setReportRequest(request);
            report.setReportType("VALORANT_PRO");
            report.setReportData("{invalid-json}");

            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));
            when(scoutingReportRepository.findByReportRequestId(1L)).thenReturn(Optional.of(report));
            when(reportJobRepository.findByReportRequestId(1L)).thenReturn(Optional.empty());

            ScoutingReportResponse response = reportService.getReport(requestId);

            assertNotNull(response);
            assertTrue(response.getSections().isEmpty());
            assertEquals(ContractVersions.SCOUTING_REPORT_V1, response.getContractVersion());
            assertEquals("legacy-v0", response.getModelVersion());
            assertEquals("legacy-v0", response.getFeatureVersion());
        }
    }
}
