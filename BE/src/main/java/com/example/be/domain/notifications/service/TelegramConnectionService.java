package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.TelegramConnectionAdapter;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramConnectionService {
    private final JdbcTemplate jdbc;
    private final NotificationChannelRepository channels;
    private final NotificationManagementService management;
    private static final SecureRandom RANDOM = new SecureRandom();
    public record Connection(String status, OffsetDateTime expiresAt) { }
    public record Link(String url, OffsetDateTime expiresAt) { }

    @Transactional
    public Link createLink(Long recipientId,String username) {
        requireActive(recipientId);
        lockRecipient(recipientId);
        LocalDateTime now=now();
        jdbc.update("UPDATE telegram_connection_tokens SET consumed_at=? WHERE recipient_id=? AND consumed_at IS NULL",now,recipientId);
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expires=now.plusMinutes(10);
        jdbc.update("INSERT INTO telegram_connection_tokens(token_hash,recipient_id,created_at,expires_at) VALUES(?,?,?,?)",hash(token),recipientId,now,expires);
        log.info("텔레그램 연결 링크를 발급했습니다. 유효기간=10분");
        return new Link("https://t.me/"+username+"?start="+token,expires.atZone(ApiTimeZone.ZONE).toOffsetDateTime());
    }
    @Transactional(readOnly=true)
    public Connection status(Long recipientId) {
        requireActive(recipientId);
        Integer linked=jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_recipient_destinations d JOIN notification_channels c ON c.id=d.channel_id
                WHERE recipient_id=? AND c.channel_type='TELEGRAM' AND d.use_yn='Y' AND d.onboarded_yn='Y'
                """,Integer.class,recipientId);
        if (linked!=null && linked>0) return new Connection("CONNECTED",null);
        List<LocalDateTime> expiry=jdbc.query("SELECT expires_at FROM telegram_connection_tokens WHERE recipient_id=? AND consumed_at IS NULL ORDER BY expires_at DESC FETCH FIRST 1 ROWS ONLY",
                (rs,n)->rs.getTimestamp(1).toLocalDateTime(),recipientId);
        if(expiry.isEmpty()) return new Connection("DISCONNECTED",null);
        return new Connection(expiry.getFirst().isAfter(now())?"WAITING":"EXPIRED",expiry.getFirst().atZone(ApiTimeZone.ZONE).toOffsetDateTime());
    }
    @Transactional
    public Connection disconnect(Long recipientId) {
        requireActive(recipientId); lockRecipient(recipientId);
        jdbc.update("DELETE FROM notification_recipient_destinations WHERE recipient_id=? AND channel_id IN (SELECT id FROM notification_channels WHERE channel_type='TELEGRAM')",recipientId);
        jdbc.update("UPDATE telegram_connection_tokens SET consumed_at=? WHERE recipient_id=? AND consumed_at IS NULL",now(),recipientId);
        return new Connection("DISCONNECTED",null);
    }
    @Transactional
    public void accept(TelegramConnectionAdapter.Update update) {
        var message=update.message();
        if(message==null || message.text()==null) return;
        if(!message.text().matches("^/start(?:@[A-Za-z0-9_]+)? [A-Za-z0-9_-]{43}$")) {
            if(message.text().startsWith("/start")) log.info("텔레그램 연결 요청 미처리. reason=START_PAYLOAD_INVALID");
            return;
        }
        if(message.chat()==null || message.from()==null || message.from().bot()
                || !"private".equals(message.chat().type()) || message.chat().id()!=message.from().id()) {
            log.info("텔레그램 연결 요청 미처리. reason=CHAT_OR_SENDER_INVALID");
            return;
        }
        String token=message.text().substring(message.text().lastIndexOf(' ')+1);
        String digest=hash(token);
        List<Long> owners=jdbc.queryForList("SELECT recipient_id FROM telegram_connection_tokens WHERE token_hash=?",Long.class,digest);
        if(owners.isEmpty()) {
            log.info("텔레그램 연결 요청 미처리. reason=LINK_NOT_FOUND");
            return;
        }
        Long recipientId=owners.getFirst(); lockRecipient(recipientId);
        if(!management.findRecipient(recipientId).isActive()) {
            log.info("텔레그램 연결 요청 미처리. reason=RECIPIENT_INACTIVE");
            return;
        }
        // One-time claim. A second update, a replaced link or an expired link cannot overwrite a binding.
        // The random nonce cannot predate issuance; Telegram's independent clock must not reject it.
        LocalDateTime now=now();
        if(jdbc.update("UPDATE telegram_connection_tokens SET consumed_at=? WHERE token_hash=? AND consumed_at IS NULL AND expires_at>?",
                now,digest,now)!=1) {
            log.info("텔레그램 연결 요청 미처리. reason=LINK_EXPIRED_REPLACED_OR_USED");
            return;
        }
        var channel=channels.findByChannelType(ChannelType.TELEGRAM).orElseThrow();
        String address=Long.toString(message.chat().id());
        Integer taken=jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipient_destinations WHERE channel_id=? AND address=? AND recipient_id<>?",Integer.class,channel.getId(),address,recipientId);
        if(taken!=null && taken>0) {
            log.info("텔레그램 연결 요청 미처리. reason=CHAT_ALREADY_LINKED");
            return; // Never steal a chat from another recipient.
        }
        jdbc.update("DELETE FROM notification_recipient_destinations WHERE recipient_id=? AND channel_id=?",recipientId,channel.getId());
        jdbc.update("INSERT INTO notification_recipient_destinations(recipient_id,channel_id,address,use_yn,onboarded_yn) VALUES(?,?,?,'Y','Y')",recipientId,channel.getId(),address);
        log.info("텔레그램 연결 요청 처리 완료.");
    }
    @Transactional
    public Long claimPolling() {
        LocalDateTime now=now();
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM telegram_connection_tokens WHERE consumed_at IS NULL AND expires_at>?",Integer.class,now);
        if(pending==null || pending==0) return null;
        if(jdbc.update("UPDATE telegram_update_cursor SET lease_until=? WHERE id=1 AND (lease_until IS NULL OR lease_until<?)",now.plusSeconds(30),now)!=1) return null;
        return jdbc.queryForObject("SELECT next_update_id FROM telegram_update_cursor WHERE id=1",Long.class);
    }
    @Transactional
    public void advance(long offset) {
        jdbc.update("UPDATE telegram_update_cursor SET next_update_id=GREATEST(next_update_id,?) WHERE id=1",offset);
    }
    @Transactional
    public void releasePolling() { jdbc.update("UPDATE telegram_update_cursor SET lease_until=NULL WHERE id=1"); }
    private void requireActive(Long id) { if(!management.findRecipient(id).isActive()) throw new GeneralException(GeneralErrorCode.BAD_REQUEST,"활성 수신자만 연결할 수 있습니다."); }
    private void lockRecipient(Long id) { jdbc.queryForObject("SELECT id FROM notification_recipients WHERE id=? FOR UPDATE",Long.class,id); }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
}
