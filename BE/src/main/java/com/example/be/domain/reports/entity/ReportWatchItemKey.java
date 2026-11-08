package com.example.be.domain.reports.entity;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content identity prevents a report replacement from promoting a different watch at the same index. */
public final class ReportWatchItemKey {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ReportWatchItemKey() { }

    public static String of(ReportContent.WatchItem item) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(item).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
