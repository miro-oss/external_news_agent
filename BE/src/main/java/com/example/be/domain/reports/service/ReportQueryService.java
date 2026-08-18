package com.example.be.domain.reports.service;

import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.global.apiPayload.PageResponse;

public interface ReportQueryService {

    PageResponse<ReportResDTO.Summary> getReports(String from, String to, int page, int size);

    ReportResDTO.Detail getLatest(boolean includeFindings);

    ReportResDTO.Detail getReport(Long reportId, boolean includeFindings);
}
