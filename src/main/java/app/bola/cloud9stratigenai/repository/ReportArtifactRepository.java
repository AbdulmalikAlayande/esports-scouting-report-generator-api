package app.bola.cloud9stratigenai.repository;

import app.bola.cloud9stratigenai.model.ReportArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportArtifactRepository extends JpaRepository<ReportArtifact, Long> {

    Optional<ReportArtifact> findByReportRequestId(Long reportRequestId);

    boolean existsByReportRequestPublicId(String publicId);
}
