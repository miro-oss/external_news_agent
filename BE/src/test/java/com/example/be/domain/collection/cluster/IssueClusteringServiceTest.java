package com.example.be.domain.collection.cluster;

import com.example.be.domain.notifications.service.WatchNotificationDeliveryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IssueClusteringServiceTest {

    @Test
    void loadsComputesAndWritesWithoutHoldingAnOuterTransaction() {
        IssueClusteringLoader loader = mock(IssueClusteringLoader.class);
        IssueClusterer clusterer = mock(IssueClusterer.class);
        IssueClusterWriter writer = mock(IssueClusterWriter.class);
        WatchNotificationDeliveryService deliveryService = mock(WatchNotificationDeliveryService.class);
        IssueClusteringService service = new IssueClusteringService(
                loader, clusterer, writer, deliveryService);
        ClusterPlan plan = new ClusterPlan(List.of(), List.of(), List.of());
        when(loader.loadInitialChanges(42L, Set.of(7L)))
                .thenReturn(new IssueClusteringLoader.Changes(List.of(), Set.of(7L)));
        when(clusterer.clusterChanges(List.of(), Set.of(7L))).thenReturn(plan);

        service.cluster(42L, Set.of(7L));

        var order = inOrder(loader, clusterer, writer, deliveryService);
        order.verify(loader).loadInitialChanges(42L, Set.of(7L));
        order.verify(clusterer).clusterChanges(List.of(), Set.of(7L));
        order.verify(writer).write(plan);
        order.verify(deliveryService).deliverPending();
    }

    @Test
    void unchangedSearchSkipsClusteringAndWritingButStillRetriesPendingWatchDeliveries() {
        IssueClusteringLoader loader = mock(IssueClusteringLoader.class);
        IssueClusterer clusterer = mock(IssueClusterer.class);
        IssueClusterWriter writer = mock(IssueClusterWriter.class);
        WatchNotificationDeliveryService delivery = mock(WatchNotificationDeliveryService.class);
        IssueClusteringService service = new IssueClusteringService(loader, clusterer, writer, delivery);
        when(loader.loadChanges(42L, Set.of(), Set.of(7L)))
                .thenReturn(new IssueClusteringLoader.Changes(List.of(), Set.of()));

        service.clusterChanges(42L, Set.of(), Set.of(7L));

        verifyNoInteractions(clusterer, writer);
        verify(delivery).deliverPending();
    }
}
