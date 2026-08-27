package com.example.be.domain.notifications.entity;

import com.example.be.domain.notifications.converter.ChannelConfigConverter;
import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.BatchSize;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "notification_channels")
@BatchSize(size = 16)
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20, unique = true)
    private ChannelType channelType;

    @Column(nullable = false, length = 100)
    private String name;

    @Builder.Default
    @Convert(converter = ChannelConfigConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "config_json", nullable = false)
    private Map<String, Object> config = new LinkedHashMap<>();

    @Column(name = "max_length", nullable = false)
    private int maxLength;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1)
    private boolean active;

    public void update(String name, Map<String, Object> config, int maxLength, boolean active) {
        this.name = name;
        this.config = new LinkedHashMap<>(config);
        this.maxLength = maxLength;
        this.active = active;
    }
}
