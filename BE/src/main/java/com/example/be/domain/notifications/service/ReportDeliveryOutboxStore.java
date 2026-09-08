package com.example.be.domain.notifications.service;

import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportDeliveryOutboxStore {
    private final JdbcTemplate jdbc;
    public record Work(Long id, Long reportId, Long recipientId, Long channelId, String batchId,
                       String name, String address, String subject, String body, int attempts) { }
    public record Delivery(Long id, String recipientName, String channelType, String status, int attempts, String message) { }
    private record ExpiredWork(Work work, String channelType) { }

    @Transactional
    public List<Work> claim() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        // Sending after a crash is ambiguous: do not silently send a second copy.
        List<ExpiredWork> expired = jdbc.query("""
                SELECT o.*,c.channel_type FROM report_notification_outbox o
                JOIN notification_channels c ON c.id=o.channel_id
                WHERE o.status='PROCESSING' AND o.processing_at<? ORDER BY o.id FETCH FIRST 30 ROWS ONLY
                """, (rs, row) -> new ExpiredWork(readWork(rs), rs.getString("channel_type")), now.minusMinutes(5));
        for (ExpiredWork item : expired) {
            finish(item.work(), item.channelType(), "UNKNOWN", null,
                    "전송 중 연결이 끊겨 결과를 확인하지 못했습니다. 수신 여부를 확인해 주세요.", false);
        }
        // Claim immediately before sending: work waiting behind slow recipients must not expire as if it had been sent.
        List<Long> candidates = jdbc.queryForList("SELECT id FROM report_notification_outbox WHERE status='PENDING' AND available_at<=? ORDER BY id FETCH FIRST 1 ROW ONLY", Long.class, now);
        java.util.ArrayList<Work> claimed = new java.util.ArrayList<>();
        for (Long id : candidates) {
            if (jdbc.update("UPDATE report_notification_outbox SET status='PROCESSING', processing_at=?, attempt_count=attempt_count+1 WHERE id=? AND status='PENDING' AND available_at<=?", now, id, now) == 1) {
                claimed.add(jdbc.queryForObject("SELECT * FROM report_notification_outbox WHERE id=?", (rs, n) -> readWork(rs), id));
            }
        }
        return claimed;
    }

    private Work readWork(ResultSet rs) throws SQLException {
        return new Work(rs.getLong("id"), rs.getLong("report_id"), rs.getLong("recipient_id"),
                rs.getLong("channel_id"), rs.getString("batch_id"), rs.getString("recipient_name"),
                rs.getString("address"), rs.getString("subject"), rs.getString("body"), rs.getInt("attempt_count"));
    }

    @Transactional(readOnly = true)
    public boolean destinationStillActive(Work work) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_recipient_destinations d
                JOIN notification_recipients r ON r.id=d.recipient_id
                JOIN notification_channels c ON c.id=d.channel_id
                WHERE r.id=? AND c.id=? AND r.active_yn='Y' AND c.active_yn='Y'
                  AND d.use_yn='Y' AND d.address=? AND (c.channel_type='EMAIL' OR d.onboarded_yn='Y')
                """, Integer.class, work.recipientId(), work.channelId(), work.address());
        return count != null && count > 0;
    }

    @Transactional
    public void finish(Work work, String channelType, String status, String externalId, String error, boolean retryable) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        boolean retry = "FAILED".equals(status) && retryable && work.attempts() < 5;
        String nextStatus = retry ? "PENDING" : status;
        if (jdbc.update("UPDATE report_notification_outbox SET status=?,last_error=?,available_at=? WHERE id=? AND status='PROCESSING'",
                nextStatus, error, now.plusSeconds(Math.min(3600, 30L << Math.min(work.attempts(), 6))), work.id()) != 1) return;
        // UNKNOWN is intentionally outside the legacy delivery enum; retain a descriptive FAILED log.
        String logStatus = "UNKNOWN".equals(status) ? "FAILED" : status;
        jdbc.update("""
                INSERT INTO notification_delivery_logs(delivery_batch_id,report_id,recipient_id,recipient_name,channel_id,
                  channel_type,address,status,external_message_id,chunk_seq,chunk_count,error_message,sent_at)
                VALUES(?,?,?,?,?,?,?,?,?,1,1,?,?)
                """, work.batchId(), work.reportId(), work.recipientId(), work.name(), work.channelId(), channelType,
                work.address(), logStatus, externalId, error, now);
        if (!retry) jdbc.update("UPDATE notification_delivery_batches SET completed_at=? WHERE id=?", now, work.batchId());
    }

    @Transactional(readOnly = true)
    public List<Delivery> deliveries(Long reportId) {
        return jdbc.query("SELECT o.*,c.channel_type FROM report_notification_outbox o JOIN notification_channels c ON c.id=o.channel_id WHERE report_id=? ORDER BY o.id",
                (rs,n) -> new Delivery(rs.getLong("id"),rs.getString("recipient_name"),rs.getString("channel_type"),
                        rs.getString("status"),rs.getInt("attempt_count"),rs.getString("last_error")),reportId);
    }

    @Transactional
    public int retryFailed(Long reportId) {
        // Successful and uncertain deliveries are never included in this retry.
        return jdbc.update("UPDATE report_notification_outbox SET status='PENDING',attempt_count=0,available_at=?,last_error=NULL WHERE report_id=? AND status='FAILED'",
                LocalDateTime.now(ApiTimeZone.ZONE), reportId);
    }
}
