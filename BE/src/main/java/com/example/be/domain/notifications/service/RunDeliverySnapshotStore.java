package com.example.be.domain.notifications.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Absence means legacy policy lookup; a disabled row is an explicit delivery override. */
@Repository
@RequiredArgsConstructor
public class RunDeliverySnapshotStore {
    private final JdbcTemplate jdbc;

    public record Target(Long recipientId, String recipientName, Long channelId, String address) { }
    public record Snapshot(String mode, boolean enabled, boolean run, boolean daily, List<Target> targets) {
        public Snapshot { targets = List.copyOf(targets); }
    }

    @Transactional
    public void save(Long runId, Snapshot snapshot) {
        jdbc.update("INSERT INTO run_delivery_settings(run_id,delivery_mode,enabled_yn,run_yn,daily_yn) VALUES(?,?,?,?,?)",
                runId, snapshot.mode(), yn(snapshot.enabled()), yn(snapshot.run()), yn(snapshot.daily()));
        if (!snapshot.targets().isEmpty()) jdbc.batchUpdate(
                "INSERT INTO run_delivery_targets(run_id,recipient_id,channel_id,recipient_name,address) VALUES(?,?,?,?,?)",
                snapshot.targets(), 500, (statement, target) -> {
                    statement.setLong(1, runId);
                    statement.setLong(2, target.recipientId());
                    statement.setLong(3, target.channelId());
                    statement.setString(4, target.recipientName());
                    statement.setString(5, target.address());
                });
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> find(Long runId) {
        var settings = jdbc.query("SELECT delivery_mode,enabled_yn,run_yn,daily_yn FROM run_delivery_settings WHERE run_id=?",
                (rs, row) -> new Snapshot(rs.getString("delivery_mode"), "Y".equals(rs.getString("enabled_yn")),
                        "Y".equals(rs.getString("run_yn")), "Y".equals(rs.getString("daily_yn")), List.of()), runId);
        if (settings.isEmpty()) return Optional.empty();
        Snapshot setting = settings.getFirst();
        var targets = jdbc.query("SELECT recipient_id,channel_id,recipient_name,address FROM run_delivery_targets WHERE run_id=? ORDER BY recipient_id,channel_id",
                (rs, row) -> new Target(rs.getLong("recipient_id"), rs.getString("recipient_name"),
                        rs.getLong("channel_id"), rs.getString("address")), runId);
        return Optional.of(new Snapshot(setting.mode(), setting.enabled(), setting.run(), setting.daily(), targets));
    }

    private static String yn(boolean value) { return value ? "Y" : "N"; }
}
