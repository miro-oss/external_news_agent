package com.example.be.domain.dart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class DartCompanySynchronizer {
    private final DartClient client;
    private final DartProperties properties;
    private final DartCompanyRepository repository;
    private final DartCompanyService service;

    @Scheduled(initialDelayString = "${news.dart.catalog.initial-delay-ms:5000}",
            fixedDelayString = "${news.dart.catalog.sync-interval-ms:86400000}", scheduler = "dartScheduler")
    public synchronized void synchronize() {
        if (!properties.isEnabled()) return;
        try {
            var companies = new DartCompanyArchive().parse(client.companyArchive());
            repository.replace(companies);
            service.syncFinished(true);
            log.info("DART 회사 목록 동기화 완료. count={}", companies.size());
        } catch (RuntimeException exception) {
            service.syncFinished(false);
            // Remote exception messages and causes may contain the key; never log them.
            log.warn("DART 회사 목록 동기화 실패. status={}",
                    exception instanceof DartUpstreamException error ? error.getStatus() : "STORAGE");
        }
    }
}
