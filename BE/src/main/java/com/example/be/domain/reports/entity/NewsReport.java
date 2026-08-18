package com.example.be.domain.reports.entity;

import com.example.be.domain.collection.entity.CollectionRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "news_reports")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsReport {

    public static final int MAX_TITLE_LENGTH = 500;
    public static final int MAX_MODEL_NAME_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private CollectionRun run;

    @Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "markdown_body", nullable = false)
    private String markdownBody;

    @Column(name = "model_name", nullable = false, length = MAX_MODEL_NAME_LENGTH)
    private String modelName;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
}
