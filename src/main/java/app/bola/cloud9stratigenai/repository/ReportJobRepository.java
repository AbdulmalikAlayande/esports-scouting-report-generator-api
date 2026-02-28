package app.bola.cloud9stratigenai.repository;

import app.bola.cloud9stratigenai.model.ReportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportJobRepository extends JpaRepository<ReportJob, Long> {

    Optional<ReportJob> findByReportRequestId(Long reportRequestId);

    Optional<ReportJob> findByReportRequestPublicId(String publicId);
}
