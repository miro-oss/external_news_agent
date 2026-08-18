package com.example.be.domain.sources.service.query;

import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.sources.converter.SourceConverter;
import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.exception.SourceException;
import com.example.be.domain.sources.exception.code.SourceErrorCode;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.sources.repository.SourceSpecification;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceQueryServiceImpl implements SourceQueryService {

    private final SourceRepository sourceRepository;
    private final CollectionRunItemRepository runItemRepository;

    @Override
    public PageResponse<SourceResDTO.Summary> getSources(String sourceKind,
                                                         Boolean active,
                                                         String keyword,
                                                         int page,
                                                         int size) {
        validatePaging(page, size);
        validateSourceKind(sourceKind);

        Page<Source> sources = sourceRepository.findAll(
                SourceSpecification.filter(sourceKind, active, keyword),
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"))
        );

        return PageResponse.of(
                SourceConverter.toSummaryList(sources.getContent(), countLinkedTopics(sources.getContent())),
                page,
                size,
                sources.getTotalElements()
        );
    }

    @Override
    public SourceResDTO.Detail getSource(Long sourceId) {
        Source source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new SourceException(SourceErrorCode.SOURCE_NOT_FOUND));

        return SourceConverter.toDetail(source,
                runItemRepository.findFirstBySourceIdOrderByRunStartedAtDescRunIdDescIdDesc(sourceId)
                        .map(item -> item.getRun())
                        .orElse(null));
    }

    private Map<Long, Integer> countLinkedTopics(List<Source> sources) {
        if (sources.isEmpty()) {
            return Map.of();
        }

        List<Long> sourceIds = sources.stream().map(Source::getId).toList();
        return sourceRepository.countLinkedTopics(sourceIds).stream()
                .collect(Collectors.toMap(
                        SourceRepository.LinkedTopicCount::getSourceId,
                        SourceRepository.LinkedTopicCount::getLinkedTopicCount
                ));
    }

    private void validateSourceKind(String sourceKind) {
        if (!StringUtils.hasText(sourceKind)) {
            return;
        }

        String normalized = sourceKind.trim().toUpperCase(Locale.ROOT);
        if (!Source.KIND_FEED.equals(normalized) && !Source.KIND_SEARCH.equals(normalized)) {
            throw new SourceException(SourceErrorCode.INVALID_SOURCE_KIND);
        }
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
