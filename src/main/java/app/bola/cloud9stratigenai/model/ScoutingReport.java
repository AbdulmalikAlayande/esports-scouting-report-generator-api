package app.bola.cloud9stratigenai.model;

import jakarta.persistence.Entity;

import app.bola.cloud9stratigenai.common.model.BaseModel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "scouting_reports")
public class ScoutingReport extends BaseModel {
	
	@OneToOne
	@JoinColumn(name = "report_request_id", unique = true)
	private ReportRequest reportRequest;
	
	@Column(name = "report_type")
	private String reportType;
	
	@JdbcTypeCode(value = SqlTypes.JSON)
	@Column(name = "report_data", columnDefinition = "jsonb")
	private String reportData;  // Full report JSON
	
	@Column(name = "generated_report", columnDefinition = "TEXT")
	private String generatedReport;
}
