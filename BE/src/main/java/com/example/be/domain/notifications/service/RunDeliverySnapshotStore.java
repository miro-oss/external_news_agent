package com.example.be.domain.notifications.service;

import com.example.be.global.converter.LongListJsonConverter;
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
    private static final LongListJsonConverter IDS = new LongListJsonConverter();

    public record Target(Long recipientId, String recipientName, Long channelId, String address) { }
    public record Selection(List<Long> groupIds, List<Long> recipientIds, List<Long> channelIds) { }
    public record Snapshot(String mode, boolean enabled, boolean run, boolean daily, List<Target> targets,
                           List<Long> groupIds, List<Long> recipientIds, List<Long> channelIds) {
        public Snapshot {
            targets = List.copyOf(targets);
            groupIds = groupIds == null ? null : List.copyOf(groupIds);
            recipientIds = recipientIds == null ? null : List.copyOf(recipientIds);
            channelIds = channelIds == null ? null : List.copyOf(channelIds);
        }

        /** Pre-migration snapshots contain exact target pairs but no original picker selection. */
        public Snapshot(String mode, boolean enabled, boolean run, boolean daily, List<Target> targets) {
            this(mode, enabled, run, daily, targets, null, null, null);
        }

        public Selection selection() {
            return new Selection(
                    groupIds == null ? List.of() : groupIds,
                    recipientIds == null ? targets.stream().map(Target::recipientId).distinct().toList() : recipientIds,
                    channelIds == null ? targets.stream().map(Target::channelId).distinct().toList() : channelIds);
        }
    }

    @Transactional
    public void save(Long runId, Snapshot snapshot) {
        jdbc.update("INSERT INTO run_delivery_settings(run_id,delivery_mode,enabled_yn,run_yn,daily_yn,group_ids,recipient_ids,channel_ids) VALUES(?,?,?,?,?,?,?,?)",
                runId, snapshot.mode(), yn(snapshot.enabled()), yn(snapshot.run()), yn(snapshot.daily()),
                json(snapshot.groupIds()), json(snapshot.recipientIds()), json(snapshot.channelIds()));
        saveTargets(runId, snapshot);
    }

    /** The caller holds the collection run row lock through commit. */
    @Transactional
    public void replace(Long runId, Snapshot snapshot) {
        int updated = jdbc.update("UPDATE run_delivery_settings SET delivery_mode=?,enabled_yn=?,run_yn=?,daily_yn=?,group_ids=?,recipient_ids=?,channel_ids=? WHERE run_id=?",
                snapshot.mode(), yn(snapshot.enabled()), yn(snapshot.run()), yn(snapshot.daily()),
                json(snapshot.groupIds()), json(snapshot.recipientIds()), json(snapshot.channelIds()), runId);
        if (updated == 0) {
            save(runId, snapshot);
            return;
        }
        jdbc.update("DELETE FROM run_delivery_targets WHERE run_id=?", runId);
        saveTargets(runId, snapshot);
    }

    private void saveTargets(Long runId, Snapshot snapshot) {
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
        var settings = jdbc.query("SELECT delivery_mode,enabled_yn,run_yn,daily_yn,group_ids,recipient_ids,channel_ids FROM run_delivery_settings WHERE run_id=?",
                (rs, row) -> new Snapshot(rs.getString("delivery_mode"), "Y".equals(rs.getString("enabled_yn")),
                        "Y".equals(rs.getString("run_yn")), "Y".equals(rs.getString("daily_yn")), List.of(),
                        ids(rs.getString("group_ids")), ids(rs.getString("recipient_ids")), ids(rs.getString("channel_ids"))), runId);
        if (settings.isEmpty()) return Optional.empty();
        Snapshot setting = settings.getFirst();
        var targets = jdbc.query("SELECT recipient_id,channel_id,recipient_name,address FROM run_delivery_targets WHERE run_id=? ORDER BY recipient_id,channel_id",
                (rs, row) -> new Target(rs.getLong("recipient_id"), rs.getString("recipient_name"),
                        rs.getLong("channel_id"), rs.getString("address")), runId);
        return Optional.of(new Snapshot(setting.mode(), setting.enabled(), setting.run(), setting.daily(), targets,
                setting.groupIds(), setting.recipientIds(), setting.channelIds()));
    }

    private static String json(List<Long> values) { return values == null ? null : IDS.convertToDatabaseColumn(values); }
    private static List<Long> ids(String json) { return json == null ? null : IDS.convertToEntityAttribute(json); }
    private static String yn(boolean value) { return value ? "Y" : "N"; }
}
