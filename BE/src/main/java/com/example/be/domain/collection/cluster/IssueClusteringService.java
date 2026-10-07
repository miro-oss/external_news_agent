package com.example.be.domain.collection.cluster;

import com.example.be.domain.notifications.service.WatchNotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 트랜잭션 없는 조정자: 읽기 → 계산 → 짧은 쓰기 경계를 명시한다. */
@Service
@RequiredArgsConstructor
public class IssueClusteringService {

    private final IssueClusteringLoader loader;
    private final IssueClusterer clusterer;
    private final IssueClusterWriter writer;
    private final WatchNotificationDeliveryService watchNotificationDeliveryService;

    public void cluster(Long runId) {
        cluster(runId, Set.of());
    }

    public void cluster(Long runId, Set<Long> refreshedArticleIds) {
        writeChanges(loader.loadInitialChanges(runId, refreshedArticleIds));
    }

    public void clusterChanges(Long runId, Set<Long> changedArticleIds, Set<Long> observedArticleIds) {
        writeChanges(loader.loadChanges(runId, changedArticleIds, observedArticleIds));
    }

    private void writeChanges(IssueClusteringLoader.Changes changes) {
        if (changes.changedArticleIds().isEmpty()) {
            // Pending watch deliveries must still retry when this run found no new evidence.
            watchNotificationDeliveryService.deliverPending();
            return;
        }
        ClusterPlan plan = clusterer.clusterChanges(changes.articles(), changes.changedArticleIds());
        writer.write(plan);
        watchNotificationDeliveryService.deliverPending();
    }
}
