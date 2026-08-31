package com.example.be.domain.collection.service.query;

import com.example.be.domain.collection.converter.CollectionRunConverter;
import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.repository.CollectionRunSpecification;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CollectionRunQueryServiceImpl implements CollectionRunQueryService {

    private final CollectionRunRepository runRepository;
    private final CollectionRunItemRepository runItemRepository;
    private final CollectionRunCoverageService coverageService;

    @Override
    public PageResponse<CollectionRunResDTO.Summary> getRuns(String status,
                                                             String triggerType,
                                                             Long topicId,
                                                             OffsetDateTime from,
                                                             OffsetDateTime to,
                                                             int page,
                                                             int size) {
        validatePaging(page, size);
        validatePeriod(from, to);

        Page<CollectionRun> runs = runRepository.findAll(
                CollectionRunSpecification.filter(
                        parseStatus(status),
                        parseTriggerType(triggerType),
                        topicId,
                        toLocalDateTime(from),
                        toLocalDateTime(to)),
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("startedAt"),
                        Sort.Order.desc("id")))
        );

        Map<Long, Integer> warningCounts = countWarnings(runs.getContent());
        return PageResponse.of(
                runs.getContent().stream()
                        .map(run -> CollectionRunConverter.toSummary(
                                run, warningCounts.getOrDefault(run.getId(), 0)))
                        .toList(),
                page,
                size,
                runs.getTotalElements()
        );
    }

    @Override
    public CollectionRunResDTO.Detail getRun(Long runId) {
        CollectionRun run = runRepository.findById(runId)
                .orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));
        List<CollectionRunItem> items = runItemRepository.findByRunIdOrderByIdAsc(runId);
        List<CollectionRunWarning> warnings = List.copyOf(run.getWarnings());

        return CollectionRunConverter.toDetail(run, items, warnings, coverageService.calculate(runId));
    }

    private Map<Long, Integer> countWarnings(List<CollectionRun> runs) {
        if (runs.isEmpty()) {
            return Map.of();
        }

        return runRepository.countWarnings(runs.stream().map(CollectionRun::getId).toList())
                .stream()
                .collect(Collectors.toMap(
                        CollectionRunRepository.WarningCount::getRunId,
                        CollectionRunRepository.WarningCount::getWarningCount
                ));
    }

    private RunStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return RunStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "status는 PENDING / RUNNING / SUCCESS / PARTIAL / FAILED 중 하나여야 합니다.");
        }
    }

    private TriggerType parseTriggerType(String triggerType) {
        if (!StringUtils.hasText(triggerType)) {
            return null;
        }
        try {
            return TriggerType.valueOf(triggerType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "triggerType은 MANUAL / SCHEDULED 중 하나여야 합니다.");
        }
    }

    private void validatePeriod(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "from은 to보다 이전이어야 합니다.");
        }
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ApiTimeZone.ZONE).toLocalDateTime();
    }

    private void validatePaging(int page, int size) {
        if (page < PageResponse.DEFAULT_PAGE) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (size < PageResponse.MIN_SIZE || size > PageResponse.MAX_SIZE) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "size는 " + PageResponse.MIN_SIZE + " 이상 " + PageResponse.MAX_SIZE + " 이하여야 합니다.");
        }
    }
}
