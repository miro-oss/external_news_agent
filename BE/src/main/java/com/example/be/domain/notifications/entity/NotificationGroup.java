package com.example.be.domain.notifications.entity;

import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.BatchSize;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "notification_groups")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private GroupPerspective perspective;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @BatchSize(size = 100)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "notification_group_members",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "recipient_id"))
    private List<NotificationRecipient> members = new ArrayList<>();

    public void update(String name, GroupPerspective perspective, boolean active) {
        this.name = name;
        this.perspective = perspective;
        this.active = active;
    }

    public void replaceMembers(List<NotificationRecipient> recipients) {
        members.clear();
        members.addAll(recipients);
    }
}
