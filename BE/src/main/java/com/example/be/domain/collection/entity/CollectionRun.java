package com.example.be.domain.collection.entity;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.global.converter.YnBooleanConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

/**
 * 수집 실행 1건. 화면의 "지금 실행" 한 번이 이 행 하나다.
 *
 * <p>집계 카운트는 조합별 값의 합계를 들고 있는 비정규화다. 내역 조회가 실행마다 조합을 훑지 않게 하려는 것이다.
 */
@Entity
@Table(name = "news_collection_runs")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRun {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private TriggerType triggerType;

    @Column(name = "idempotency_key", length = MAX_IDEMPOTENCY_KEY_LENGTH)
    private String idempotencyKey;

    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "force_refresh_yn", nullable = false, length = 1)
    private boolean forceRefresh;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "scanned_count", nullable = false)
    private int scannedCount;

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    /** 실행 종료 시 생성된 보고서의 역참조. 보고서 쪽 run_id와 함께 애플리케이션이 같은 쌍으로 연결한다. */
    @Column(name = "report_id")
    private Long reportId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "llm_plan", nullable = false, length = 10)
    private AgentPlan llmPlan = AgentPlan.FREE;

    @Builder.Default
    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CollectionRunItem> items = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CollectionRunWarning> warnings = new ArrayList<>();

    public void addItem(CollectionRunItem item) {
        items.add(item);
        item.assignRun(this);
    }

    public void addWarning(CollectionRunWarning warning) {
        warnings.add(warning);
        warning.assignRun(this);
    }

    public void start() {
        this.status = RunStatus.RUNNING;
    }

    /**
     * 조합별 결과를 합쳐 실행을 닫는다. 하나라도 실패했으면 PARTIAL이고, 전부 실패면 FAILED다.
     * 크롤러 하나가 죽었다고 실행 전체를 실패로 적으면 나머지 성공한 수집이 묻힌다.
     */
    public void finish(LocalDateTime finishedAt) {
        requireEveryItemTerminal();

        this.scannedCount = items.stream().mapToInt(CollectionRunItem::getScannedCount).sum();
        this.newCount = items.stream().mapToInt(CollectionRunItem::getNewCount).sum();
        this.updatedCount = items.stream().mapToInt(CollectionRunItem::getUpdatedCount).sum();
        this.skippedCount = this.scannedCount - this.newCount - this.updatedCount;
        this.status = resolveStatus();
        this.finishedAt = finishedAt;
    }

    public void fail(LocalDateTime finishedAt) {
        this.status = RunStatus.FAILED;
        this.finishedAt = finishedAt;
    }

    /**
     * 끝나지 않은 조합을 실패로 닫고 실행을 마감한다. 스레드풀 거절이나 프로세스 종료처럼
     * <b>아무도 조합을 닫아 주지 않는 경로</b>에서 쓴다.
     *
     * <p>상태는 {@link #finish}가 정한다 — 이미 성공한 조합이 있으면 FAILED가 아니라 PARTIAL이다.
     * 중간에 끊긴 실행에서 앞부분의 성공한 수집까지 실패로 적으면 이력이 사실과 달라진다.
     */
    public void abort(LocalDateTime finishedAt) {
        items.stream()
                .filter(item -> item.getStatus() == RunItemStatus.PENDING
                        || item.getStatus() == RunItemStatus.RUNNING)
                .forEach(CollectionRunItem::markFailed);

        finish(finishedAt);
    }

    public void attachReport(Long reportId) {
        this.reportId = reportId;
    }

    public int getWarningCount() {
        return warnings.size();
    }

    /**
     * 아직 도는 조합이 있는데 실행을 닫으면 SUCCESS로 기록될 수 있다. 이력에 거짓 성공이 남는 쪽이
     * 예외로 멈추는 것보다 나쁘다.
     */
    private void requireEveryItemTerminal() {
        List<Long> pendingItemIds = items.stream()
                .filter(item -> item.getStatus() == RunItemStatus.PENDING
                        || item.getStatus() == RunItemStatus.RUNNING)
                .map(CollectionRunItem::getId)
                .toList();

        if (!pendingItemIds.isEmpty()) {
            throw new IllegalStateException(
                    "아직 끝나지 않은 조합이 있어 실행을 닫을 수 없다. itemIds=" + pendingItemIds);
        }
    }

    private RunStatus resolveStatus() {
        if (items.isEmpty()) {
            return warnings.isEmpty() ? RunStatus.SUCCESS : RunStatus.PARTIAL;
        }

        boolean anyFailed = items.stream().anyMatch(item -> item.getStatus() == RunItemStatus.FAILED);
        boolean allFailed = items.stream().allMatch(item -> item.getStatus() == RunItemStatus.FAILED);
        boolean anyPartial = items.stream().anyMatch(item -> item.getStatus() == RunItemStatus.PARTIAL);

        if (allFailed) {
            return RunStatus.FAILED;
        }
        if (anyFailed || anyPartial || !warnings.isEmpty()) {
            return RunStatus.PARTIAL;
        }
        return RunStatus.SUCCESS;
    }
}
