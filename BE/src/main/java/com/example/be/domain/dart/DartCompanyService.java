package com.example.be.domain.dart;

import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DartCompanyService {
    public enum Status { READY, PREPARING, DISABLED, FAILED }
    public record SearchResult(Status status, String message, List<DartModels.Company> content,
                               int page, int size, long totalElements, int totalPages, boolean hasNext) { }
    private final DartCompanyRepository repository;
    private final DartProperties properties;
    private volatile boolean lastSyncFailed;

    public SearchResult search(String query, int page, int size) {
        query = query == null ? "" : query.trim();
        if (query.length() < 2 || query.length() > 100)
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "query는 2자 이상 100자 이하여야 합니다.");
        if (page < 0) throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        if (size < 1 || size > 100)
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "size는 1 이상 100 이하여야 합니다.");
        if (!properties.isEnabled()) return empty(Status.DISABLED, "DART API 키가 설정되지 않았습니다.", page, size);
        if (!repository.hasCompanies()) return lastSyncFailed
                ? empty(Status.FAILED, "공시 회사 목록을 불러오지 못했습니다.", page, size)
                : empty(Status.PREPARING, "공시 회사 목록을 준비하고 있습니다.", page, size);
        long total = repository.count(query);
        int pages = (int) ((total + size - 1) / size);
        return new SearchResult(Status.READY, "공시 회사를 검색했습니다.", repository.search(query, page, size),
                page, size, total, pages, (long) page + 1 < pages);
    }

    public DartModels.Company requireCompany(String corpCode) {
        if (corpCode == null || !corpCode.matches("[0-9]{8}")) throw invalidCompany();
        return repository.find(corpCode).orElseThrow(this::invalidCompany);
    }

    void syncFinished(boolean succeeded) { lastSyncFailed = !succeeded; }
    private TopicException invalidCompany() {
        return new TopicException(TopicErrorCode.DART_COMPANY_NOT_FOUND);
    }
    private SearchResult empty(Status status, String message, int page, int size) {
        return new SearchResult(status, message, List.of(), page, size, 0, 0, false);
    }
}
