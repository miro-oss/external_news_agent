package com.example.be.domain.collection.entity;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 조합(주제 × 소스) 하나의 수집 결과. 상세 조회의 breakdown 한 행이고,
 * 내역 조회의 topicId 필터도 이 행의 존재로 푼다.
 */
@Entity
@Table(name = "news_collection_run_items")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private CollectionRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RunItemStatus status;

    @Column(name = "scanned_count", nullable = false)
    private int scannedCount;

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    void assignRun(CollectionRun run) {
        this.run = run;
    }

    public void recordResult(RunItemStatus status, int scannedCount, int newCount, int updatedCount) {
        this.status = status;
        this.scannedCount = scannedCount;
        this.newCount = newCount;
        this.updatedCount = updatedCount;
    }

    public void markSkipped() {
        this.status = RunItemStatus.SKIPPED;
    }

    public void markFailed() {
        this.status = RunItemStatus.FAILED;
    }

    /**
     * 이 조합에서 이미 있던 기사로 판정된 수. scanned에서 새 기사와 변경 기사를 뺀 나머지다.
     */
    public int getSkippedCount() {
        return scannedCount - newCount - updatedCount;
    }
}
