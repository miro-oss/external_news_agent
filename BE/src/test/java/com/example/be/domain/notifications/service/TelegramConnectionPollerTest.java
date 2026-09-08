package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.TelegramConnectionAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class TelegramConnectionPollerTest {

    @Test
    void repeatedEmptyResponsesLogOnlyReceptionStateChanges(CapturedOutput output) {
        var adapter=mock(TelegramConnectionAdapter.class);
        var connections=mock(TelegramConnectionService.class);
        var poller=new TelegramConnectionPoller(adapter,connections);
        when(adapter.configured()).thenReturn(true);
        when(connections.claimPolling()).thenReturn(123456L);
        when(adapter.updates(123456)).thenReturn(List.of());

        poller.poll();
        poller.poll();

        assertEquals(1,output.getOut().lines().filter(line->line.contains("수신을 시작합니다.")).count());
        assertEquals(1,output.getOut().lines().filter(line->line.contains("state=EMPTY")).count());

        when(adapter.updates(123456)).thenReturn(List.of(new TelegramConnectionAdapter.Update(123456,null)));
        poller.poll();
        poller.poll();
        assertEquals(1,output.getOut().lines().filter(line->line.contains("state=RECEIVED")).count());
        assertFalse(output.getAll().contains("123456"));

        when(connections.claimPolling()).thenReturn(null);
        poller.poll();
        when(connections.claimPolling()).thenReturn(123456L);
        when(adapter.updates(123456)).thenReturn(List.of());
        poller.poll();
        assertEquals(2,output.getOut().lines().filter(line->line.contains("수신을 시작합니다.")).count());
        assertEquals(2,output.getOut().lines().filter(line->line.contains("state=EMPTY")).count());
    }

    @Test
    void failedBindingPreservesOffsetForRetryAndLogsNoPrivateDetails(CapturedOutput output) {
        var adapter = mock(TelegramConnectionAdapter.class);
        var connections = mock(TelegramConnectionService.class);
        var update = new TelegramConnectionAdapter.Update(10, null);
        var poller = new TelegramConnectionPoller(adapter, connections);
        when(adapter.configured()).thenReturn(true);
        when(connections.claimPolling()).thenReturn(10L);
        when(adapter.updates(10)).thenReturn(List.of(update));
        doThrow(new IllegalStateException("synthetic-private-token-and-message"))
                .doNothing().when(connections).accept(update);

        poller.poll();

        verify(connections, never()).advance(anyLong());
        verify(connections).releasePolling();
        assertTrue(output.getOut().contains("type=IllegalStateException"));
        assertFalse(output.getAll().contains("synthetic-private-token-and-message"));

        poller.poll();

        verify(connections, times(2)).accept(update);
        verify(connections).advance(11);
        verify(connections, times(2)).releasePolling();
    }
}
