package com.example.be.domain.collection.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CollectionRunAsyncService {

    private final CollectionRunExecutionService executionService;

    @Async("collectionTaskExecutor")
    public void execute(Long runId) {
        executionService.executeRun(runId);
    }
}
