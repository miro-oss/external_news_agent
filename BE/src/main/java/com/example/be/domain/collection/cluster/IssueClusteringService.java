package com.example.be.domain.collection.cluster;

import com.example.be.domain.notifications.service.WatchNotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 트랜잭션 없는 조정자: 읽기 → 계산 → 짧은 쓰기 경계를 명시한다. */
@Service
@RequiredArgsConstructor
public class IssueClusteringService {

    private final IssueClusteringLoader loader;
    private final IssueClusterer clusterer;
    private final IssueClusterWriter writer;
    private final WatchNotificationDeliveryService watchNotificationDeliveryService;

    public void cluster(Long runId) {
        ClusterPlan plan = clusterer.cluster(loader.load(runId));
        writer.write(plan).forEach(watchNotificationDeliveryService::deliver);
    }
}
