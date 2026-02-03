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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;

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
    private ModelMapper mapper;
    
    private ObjectMapper objectMapper;
    private ReportService reportService;
    private Validator validator;

    @BeforeEach
    void setUp() {
        // Use real ObjectMapper for JSON parsing tests
        objectMapper = new ObjectMapper();
        reportService = new ReportServiceImpl(mapper, objectMapper, reportRequestRepository, scoutingReportRepository);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("Use Case 1: Submit Report Request")
    class SubmitReportRequestTests {

        @Test
        @DisplayName("Should create a PENDING report request when given a valid prompt")
        void shouldCreatePendingRequest_WhenPromptIsValid() throws Exception {
            // Given
            String validPrompt = "This is a valid scouting report request prompt with enough length.";
            GenerateReportRequest request = new GenerateReportRequest(validPrompt);
            
            ReportRequest reportRequest = new ReportRequest();
            reportRequest.setUserPrompt(validPrompt);
            when(mapper.map(request, ReportRequest.class)).thenReturn(reportRequest);
            
            when(reportRequestRepository.save(any(ReportRequest.class))).thenAnswer(invocation -> {
                ReportRequest r = invocation.getArgument(0);
                // Simulate @PrePersist for unit test
                java.lang.reflect.Field field = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
                field.setAccessible(true);
                field.set(r, "test-uuid-123");
                return r;
            });

            // When
            ReportStatusResponse response = reportService.generateReport(request);

            // Then
            assertNotNull(response);
            assertEquals("test-uuid-123", response.getRequestId());
            assertEquals("PENDING", response.getStatus());
            
            ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
            verify(reportRequestRepository).save(captor.capture());
            ReportRequest captured = captor.getValue();
            assertEquals(validPrompt, captured.getUserPrompt());
            assertEquals(ReportRequest.ReportStatus.PENDING, captured.getStatus());
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "", 
            "Too short", 
            "A very long prompt that exceeds the maximum allowed limit of five hundred characters for a scouting report generation request. To reach this limit, I need to keep writing more and more words that don't necessarily have to make sense but should be enough to overflow the buffer or the validation logic. We are testing the boundary conditions of the application to ensure it remains stable even when users provide excessively long inputs. This is part of the aggressive testing strategy to cover all use cases. Still not enough? Let's add some more text here just in case."
        })
        @DisplayName("Should throw exception when prompt is invalid")
        void shouldThrowException_WhenPromptIsInvalid(String invalidPrompt) {
            // Given
            GenerateReportRequest request = new GenerateReportRequest(invalidPrompt);
            
            // Validation check
            var violations = validator.validate(request);
            assertFalse(violations.isEmpty(), "Validator should find violations for: " + invalidPrompt);

            // When / Then
            assertThrows(IllegalArgumentException.class, () -> {
                reportService.generateReport(request);
            });
            
            verify(reportRequestRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should handle database failure gracefully")
        void shouldHandleDatabaseFailure() {
            // Given
            GenerateReportRequest request = new GenerateReportRequest("Valid prompt for scouting report generator.");
            ReportRequest reportRequest = new ReportRequest();
            reportRequest.setUserPrompt(request.getUserPrompt());
            when(mapper.map(request, ReportRequest.class)).thenReturn(reportRequest);
            when(reportRequestRepository.save(any())).thenThrow(new RuntimeException("DB Error"));

            // When / Then
            assertThrows(RuntimeException.class, () -> {
                reportService.generateReport(request);
            });
        }

        @Test
        @DisplayName("Should handle special characters in prompt")
        void shouldHandleSpecialCharactersInPrompt() throws Exception {
            // Given
            String specialPrompt = "Scouting for @Team! With #Special chars & symbols? (Hopefully) works fine.";
            GenerateReportRequest request = new GenerateReportRequest(specialPrompt);

            ReportRequest reportRequest = new ReportRequest();
            reportRequest.setUserPrompt(specialPrompt);
            when(mapper.map(request, ReportRequest.class)).thenReturn(reportRequest);

            when(reportRequestRepository.save(any(ReportRequest.class))).thenAnswer(invocation -> {
                ReportRequest r = invocation.getArgument(0);
                java.lang.reflect.Field field = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
                field.setAccessible(true);
                field.set(r, "special-uuid");
                return r;
            });

            // When
            ReportStatusResponse response = reportService.generateReport(request);

            // Then
            assertNotNull(response);
            assertEquals("special-uuid", response.getRequestId());
            verify(reportRequestRepository).save(any(ReportRequest.class));
        }
    }

    @Nested
    @DisplayName("Use Case 3: View Completed Report")
    class ViewCompletedReportTests {

        @Test
        @DisplayName("Should return structured report when status is COMPLETED")
        void shouldReturnStructuredReport_WhenCompleted() throws Exception {
            // Given
            String requestId = "completed-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.COMPLETED);
            
            // Set publicId via reflection
            java.lang.reflect.Field publicIdField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
            publicIdField.setAccessible(true);
            publicIdField.set(request, requestId);

            // Set id via reflection
            java.lang.reflect.Field idField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, 1L);

            String reportJson = "{\"executive_summary\": \"This is a great team.\", \"tactical_analysis\": \"Use more smokes.\"}";
            ScoutingReport report = new ScoutingReport();
            report.setReportRequest(request);
            report.setReportType("VALORANT_PRO");
            report.setReportData(reportJson);
            report.setGeneratedReport("Full text summary of the report.");
            
            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));
            when(scoutingReportRepository.findByReportRequestId(1L)).thenReturn(Optional.of(report));

            // When
            ScoutingReportResponse response = reportService.getReport(requestId);

            // Then
            assertNotNull(response);
            assertEquals(requestId, response.getRequestId());
            assertEquals("VALORANT_PRO", response.getReportType());
            assertEquals("Full text summary of the report.", response.getSummary());
            assertEquals(2, response.getSections().size());
            
            boolean hasSummary = response.getSections().stream().anyMatch(s -> s.getTitle().equals("Executive summary"));
            boolean hasTactical = response.getSections().stream().anyMatch(s -> s.getTitle().equals("Tactical analysis"));
            assertTrue(hasSummary);
            assertTrue(hasTactical);
        }

        @Test
        @DisplayName("Should throw ReportNotReadyException when status is PENDING")
        void shouldThrowNotReady_WhenPending() throws Exception {
            // Given
            String requestId = "pending-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.PENDING);
            
            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));

            // When / Then
            assertThrows(ReportNotReadyException.class, () -> reportService.getReport(requestId));
        }

        @Test
        @DisplayName("Should throw ReportNotFoundException when request does not exist")
        void shouldThrowNotFound_WhenRequestMissing() {
            // Given
            String requestId = "missing-uuid";
            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.empty());

            // When / Then
            assertThrows(ReportNotFoundException.class, () -> reportService.getReport(requestId));
        }

        @Test
        @DisplayName("Should handle invalid JSON in report data gracefully")
        void shouldHandleInvalidJson() throws Exception {
            // Given
            String requestId = "bad-json-uuid";
            ReportRequest request = new ReportRequest();
            request.setStatus(ReportRequest.ReportStatus.COMPLETED);
            
            java.lang.reflect.Field publicIdField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("publicId");
            publicIdField.setAccessible(true);
            publicIdField.set(request, requestId);

            java.lang.reflect.Field idField = app.bola.cloud9stratigenai.common.model.BaseModel.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(request, 1L);

            ScoutingReport report = new ScoutingReport();
            report.setReportRequest(request);
            report.setReportType("VALORANT_PRO"); // Set reportType to avoid NPE in buildTitle
            report.setReportData("{invalid-json}");
            
            when(reportRequestRepository.findByPublicId(requestId)).thenReturn(Optional.of(request));
            when(scoutingReportRepository.findByReportRequestId(1L)).thenReturn(Optional.of(report));

            // When
            ScoutingReportResponse response = reportService.getReport(requestId);

            // Then
            assertNotNull(response);
            assertTrue(response.getSections().isEmpty());
        }
    }
}