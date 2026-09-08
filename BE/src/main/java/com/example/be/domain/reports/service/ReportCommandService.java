package com.example.be.domain.reports.service;

import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportCommandService {

    private final NewsReportRepository reportRepository;

    @Transactional
    public ReportResDTO.Deleted deleteReport(Long reportId) {
        NewsReport report = reportRepository.findByIdForUpdate(reportId)
                .filter(value -> value.getReportStatus() != ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        report.hide(LocalDateTime.now(ApiTimeZone.ZONE));
        return new ReportResDTO.Deleted(reportId, true);
    }
}
