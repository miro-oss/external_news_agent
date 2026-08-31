package com.example.be.domain.collection.cluster;

import com.example.be.domain.notifications.service.WatchNotificationDeliveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
        when(loader.load(42L)).thenReturn(List.of());
        when(clusterer.cluster(List.of())).thenReturn(plan);

        service.cluster(42L);

        var order = inOrder(loader, clusterer, writer, deliveryService);
        order.verify(loader).load(42L);
        order.verify(clusterer).cluster(List.of());
        order.verify(writer).write(plan);
        order.verify(deliveryService).deliverPending();
    }
}
