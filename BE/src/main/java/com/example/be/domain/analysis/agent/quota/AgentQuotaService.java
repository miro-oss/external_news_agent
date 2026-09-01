package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.exception.LlmException;
import com.example.be.domain.settings.exception.code.LlmErrorCode;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AgentQuotaService {

    private final AgentQuotaJdbcRepository repository;
    private final AgentProperties properties;
    private final LlmPlanService planService;

    @Transactional
    public QuotaReservation reserve(Long runId,
                                    String idempotencyKey,
                                    AgentTask task,
                                    AgentPlan plan) {
        repository.lockSingletonSettings();
        releaseExpiredReservations();
        Optional<String> existingStatus = repository.findStatusByIdempotencyKey(idempotencyKey);
        if (existingStatus.isPresent()) {
            throw new IllegalStateException(
                    "이미 사용된 quota idempotencyKey는 재사용할 수 없습니다. key="
                            + idempotencyKey + " status=" + existingStatus.get());
        }
        return createReservation(runId, idempotencyKey, task, plan);
    }

    @Transactional
    public void assertRunCanStart(AgentPlan plan) {
        if (plan != AgentPlan.PAID || !properties.isEnabled()) {
            return;
        }
        repository.lockSingletonSettings();
        releaseExpiredReservations();
        UsageWindow usage = usageWindow();
        BigDecimal units = paidMaxUnits();
        BigDecimal dailyAnalysisLimit = paidDailyLimit().subtract(reportReserve());
        if (usage.paidMonthlyUsed().add(units).compareTo(paidMonthlyLimit()) > 0
                || usage.paidDailyUsed().add(units).compareTo(paidDailyLimit()) > 0
                || usage.paidAnalysisDailyUsed().add(units).compareTo(dailyAnalysisLimit) > 0) {
            throw new LlmException(LlmErrorCode.QUOTA_EXHAUSTED, Map.of(
                    "plan", plan.name(),
                    "monthlyRemaining", remaining(paidMonthlyLimit(), usage.paidMonthlyUsed()),
                    "dailyRemaining", remaining(dailyAnalysisLimit, usage.paidAnalysisDailyUsed()),
                    "resetAt", usage.dailyResetAt()));
        }
    }

    @Transactional
    public void completeSuccess(QuotaReservation reservation, BigDecimal actualCredits) {
        completeSuccess(reservation, actualCredits, true);
    }

    @Transactional
    public void completeSuccess(QuotaReservation reservation,
                                BigDecimal actualCredits,
                                boolean providerInvoked) {
        if (!providerInvoked
                && reservation.task() != AgentTask.VERIFY_EVIDENCE
                && reservation.task() != AgentTask.SELF_CRITIQUE) {
            throw new IllegalArgumentException(
                    "provider 미호출 정산은 근거 검증 또는 자기 검증 task에서만 허용됩니다.");
        }
        BigDecimal units = BigDecimal.ZERO;
        if (providerInvoked) {
            units = reservation.plan() == AgentPlan.FREE
                    ? BigDecimal.ONE
                    : actualCredits == null ? paidUnits() : nonNegative(actualCredits);
        }
        if (units.compareTo(reservation.reservedUnits()) > 0) {
            repository.release(reservation, now());
            throw new IllegalStateException(
                    "실제 LLM 사용량이 요청당 예약 상한을 초과했습니다. actual=" + units
                            + " reserved=" + reservation.reservedUnits());
        }
        repository.consume(reservation, units, now());
    }

    @Transactional
    public void completeFailure(QuotaReservation reservation, AgentClientException exception) {
        BigDecimal observedCredits = exception.getUsage() == null
                ? null
                : exception.getUsage().credits();
        if ("BUDGET_EXCEEDED".equals(exception.getCode()) && observedCredits != null) {
            settleObservedFailure(reservation, nonNegative(observedCredits));
            return;
        }
        if (!exception.isReadTimeout()
                && ("PROVIDER_UNAVAILABLE".equals(exception.getCode())
                || "SCHEMA_VIOLATION".equals(exception.getCode()))) {
            repository.release(reservation, now());
            return;
        }
        repository.consume(reservation, reservation.reservedUnits(), now());
    }

    @Transactional
    public void completeFailure(QuotaReservation reservation, String failureCode) {
        if ("PROVIDER_UNAVAILABLE".equals(failureCode)
                || "SCHEMA_VIOLATION".equals(failureCode)) {
            repository.release(reservation, now());
            return;
        }
        repository.consume(reservation, reservation.reservedUnits(), now());
    }

    @Transactional
    public LlmSettingDTO.UsageResponse usage() {
        repository.lockSingletonSettings();
        releaseExpiredReservations();
        UsageWindow usage = usageWindow();
        BigDecimal paidDailyRemaining = remaining(paidDailyLimit(), usage.paidDailyUsed());
        BigDecimal analysisLimit = paidDailyLimit().subtract(reportReserve());
        return new LlmSettingDTO.UsageResponse(
                planService.get().plan(),
                new LlmSettingDTO.FreeUsage(
                        usage.freeDailyUsed(),
                        freeDailyLimit(),
                        remaining(freeDailyLimit(), usage.freeDailyUsed()),
                        usage.dailyResetAt()),
                new LlmSettingDTO.PaidUsage(
                        usage.paidDailyUsed(),
                        paidDailyLimit(),
                        paidDailyRemaining,
                        remaining(analysisLimit, usage.paidAnalysisDailyUsed()),
                        reportReserve(),
                        usage.paidMonthlyUsed(),
                        paidMonthlyLimit(),
                        remaining(paidMonthlyLimit(), usage.paidMonthlyUsed()),
                        usage.dailyResetAt(),
                        usage.monthlyResetAt()));
    }

    private QuotaReservation createReservation(Long runId,
                                               String idempotencyKey,
                                               AgentTask task,
                                               AgentPlan plan) {
        UsageWindow usage = usageWindow();
        BigDecimal units = plan == AgentPlan.FREE ? BigDecimal.ONE : paidMaxUnits();
        validateDailyAndMonthly(task, plan, units, usage);
        repository.insert(runId, idempotencyKey, task, plan, units, now());
        return repository.findByIdempotencyKey(idempotencyKey).orElseThrow();
    }

    private void validateDailyAndMonthly(AgentTask task,
                                         AgentPlan plan,
                                         BigDecimal units,
                                         UsageWindow usage) {
        if (plan == AgentPlan.FREE) {
            if (usage.freeDailyUsed().add(units).compareTo(freeDailyLimit()) > 0) {
                throw exhausted(plan, "FREE 일일 호출 한도를 초과했습니다.");
            }
            return;
        }

        boolean analysisTask = task == AgentTask.ANALYZE || task == AgentTask.SELF_CRITIQUE;
        BigDecimal dailyLimit = analysisTask
                ? paidDailyLimit().subtract(reportReserve())
                : paidDailyLimit();
        BigDecimal taskUsage = analysisTask
                ? usage.paidAnalysisDailyUsed()
                : usage.paidDailyUsed();
        if (taskUsage.add(units).compareTo(dailyLimit) > 0
                || usage.paidDailyUsed().add(units).compareTo(paidDailyLimit()) > 0) {
            throw exhausted(plan, analysisTask
                    ? "PAID 분석 예산을 소진했습니다. 보고서 예약분은 유지합니다."
                    : "PAID 일일 예산을 소진했습니다.");
        }
        if (usage.paidMonthlyUsed().add(units).compareTo(paidMonthlyLimit()) > 0) {
            throw exhausted(plan, "PAID 월간 예산을 소진했습니다.");
        }
    }

    private UsageWindow usageWindow() {
        LocalDate today = LocalDate.now(ApiTimeZone.ZONE);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        LocalDate month = today.withDayOfMonth(1);
        LocalDateTime monthStart = month.atStartOfDay();
        LocalDateTime monthEnd = month.plusMonths(1).atStartOfDay();
        return new UsageWindow(
                repository.usage(AgentPlan.FREE, dayStart, dayEnd),
                repository.usage(AgentPlan.PAID, dayStart, dayEnd),
                repository.analysisUsage(dayStart, dayEnd),
                repository.usage(AgentPlan.PAID, monthStart, monthEnd),
                dayEnd.atZone(ApiTimeZone.ZONE).toOffsetDateTime(),
                monthEnd.atZone(ApiTimeZone.ZONE).toOffsetDateTime());
    }

    private QuotaExceededException exhausted(AgentPlan plan, String message) {
        return new QuotaExceededException(plan, message);
    }

    private BigDecimal freeDailyLimit() {
        return BigDecimal.valueOf(properties.getQuota().getFreeDailyCalls());
    }

    private BigDecimal paidDailyLimit() {
        return BigDecimal.valueOf(properties.getQuota().getPaidDailyCredits());
    }

    private BigDecimal paidMonthlyLimit() {
        return BigDecimal.valueOf(properties.getQuota().getPaidMonthlyCredits());
    }

    private BigDecimal reportReserve() {
        return BigDecimal.valueOf(properties.getQuota().getPaidDailyReportReserve());
    }

    private BigDecimal paidUnits() {
        return properties.getQuota().getPaidCreditsPerRequest();
    }

    private BigDecimal paidMaxUnits() {
        return properties.getQuota().getPaidMaxCreditsPerRequest();
    }

    private void settleObservedFailure(QuotaReservation reservation, BigDecimal observedCredits) {
        if (observedCredits.compareTo(reservation.reservedUnits()) > 0) {
            // agent_runs가 실제 credits를 보유한다. RELEASED 예약은 legacy usage 집계에서 제외하지 않는다.
            repository.release(reservation, now());
            return;
        }
        repository.consume(reservation, observedCredits, now());
    }

    private void releaseExpiredReservations() {
        LocalDateTime finishedAt = now();
        LocalDateTime reservedBefore = finishedAt.minus(properties.getQuota().getReservationTtl());
        int released = repository.releaseExpired(reservedBefore, finishedAt);
        if (released > 0) {
            log.warn("정산되지 않은 만료 quota 예약을 해제했다. count={}", released);
        }
    }

    private BigDecimal remaining(BigDecimal limit, BigDecimal used) {
        return limit.subtract(used).max(BigDecimal.ZERO);
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ApiTimeZone.ZONE);
    }

    private record UsageWindow(BigDecimal freeDailyUsed,
                               BigDecimal paidDailyUsed,
                               BigDecimal paidAnalysisDailyUsed,
                               BigDecimal paidMonthlyUsed,
                               OffsetDateTime dailyResetAt,
                               OffsetDateTime monthlyResetAt) {
    }
}
