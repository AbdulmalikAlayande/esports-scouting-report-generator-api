package app.bola.cloud9stratigenai.controller;

import app.bola.cloud9stratigenai.dto.GenerateReportRequest;
import app.bola.cloud9stratigenai.dto.ReportStatusResponse;
import app.bola.cloud9stratigenai.dto.ScoutingReportResponse;
import app.bola.cloud9stratigenai.service.ReportService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@RestController
@AllArgsConstructor
@RequestMapping("api/reports")
public class ReportController {
	
	private static final Logger log = LoggerFactory.getLogger(ReportController.class);
	
	final ReportService reportService;
	
	@PostMapping("/generate")
	public ResponseEntity<@NonNull ReportStatusResponse> submitReport(@Valid @RequestBody GenerateReportRequest reportRequest) {
		log.info("Received report submission: {}", reportRequest);
		return ResponseEntity.ok().body(reportService.generateReport(reportRequest));
	}
	
	@GetMapping("/{id}/status")
	public ResponseEntity<@NonNull ReportStatusResponse> getReportStatus(@PathVariable String id) {
		log.info("Received report status request for report id: {}", id);
		return ResponseEntity.ok().body(reportService.getReportStatus(id));
	}
	
	@GetMapping("/{id}")
	public ResponseEntity<@NonNull ScoutingReportResponse> getReport(@PathVariable String id) {
		log.info("Received report request for report id: {}", id);
		return ResponseEntity.ok().body(reportService.getReport(id));
	}
	
}
