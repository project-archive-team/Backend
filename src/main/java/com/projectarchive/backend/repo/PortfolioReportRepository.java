package com.projectarchive.backend.repo;

import com.projectarchive.backend.domain.PortfolioReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioReportRepository extends JpaRepository<PortfolioReport, Long> {
}
