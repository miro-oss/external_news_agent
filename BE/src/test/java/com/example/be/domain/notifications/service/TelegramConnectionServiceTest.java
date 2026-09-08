package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.TelegramConnectionAdapter.*;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramConnectionServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final TelegramConnectionService service=new TelegramConnectionService(jdbc,mock(NotificationChannelRepository.class),mock(NotificationManagementService.class));
    private final String token="a".repeat(43);
    @Test void groupChatCannotBindRecipient() {
        service.accept(new Update(1,new Message("/start "+token,new Chat(-50,"group"),new User(9,false),1)));
        verifyNoInteractions(jdbc);
    }
    @Test void mismatchedSenderCannotBindRecipient() {
        service.accept(new Update(1,new Message("/start "+token,new Chat(9,"private"),new User(10,false),1)));
        verifyNoInteractions(jdbc);
    }
    @Test void plainStartAndGuessedChatAreNotConnectionTokens() {
        service.accept(new Update(1,new Message("/start",new Chat(9,"private"),new User(9,false),1)));
        service.accept(new Update(2,new Message("/start 123456",new Chat(9,"private"),new User(9,false),1)));
        verifyNoInteractions(jdbc);
    }
    @Test void tokenHashDoesNotPersistReusableToken() {
        String digest=TelegramConnectionService.hash(token);
        assertEquals(64,digest.length());
        assertNotEquals(token,digest);
        assertNotEquals(digest,TelegramConnectionService.hash("b".repeat(43)));
    }
}
