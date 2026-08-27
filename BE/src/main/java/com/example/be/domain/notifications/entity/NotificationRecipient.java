package com.example.be.domain.notifications.entity;

import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "notification_recipients")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 320)
    private String email;

    @Column(length = 1000)
    private String memo;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1)
    private boolean active;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder.Default
    @OneToMany(mappedBy = "recipient", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RecipientDestination> destinations = new ArrayList<>();

    @Builder.Default
    @ManyToMany(mappedBy = "members", fetch = FetchType.LAZY)
    private List<NotificationGroup> groups = new ArrayList<>();

    public void update(String name, String phone, String email, String memo, boolean active) {
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.memo = memo;
        this.active = active;
    }

    public void replaceDestinations(List<RecipientDestination> replacements) {
        destinations.clear();
        replacements.forEach(destination -> {
            destination.assignRecipient(this);
            destinations.add(destination);
        });
    }

    public void softDelete(LocalDateTime deletedAt) {
        this.active = false;
        this.deletedAt = deletedAt;
        this.destinations.clear();
    }
}
