package com.example.be.domain.topics.service.query;

import com.example.be.domain.topics.converter.TopicConverter;
import com.example.be.domain.topics.dto.res.TopicSourceResDTO;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TopicSourceQueryServiceImpl implements TopicSourceQueryService {

    private final TopicRepository topicRepository;

    @Override
    public TopicSourceResDTO.CombinationPage getCombinations(Long topicId,
                                                             Long sourceId,
                                                             Boolean active,
                                                             int page,
                                                             int size) {
        validatePaging(page, size);
        validateTopicFilter(topicId);

        // 정렬은 조인 별칭이 필요해 쿼리 안에 두었다. Pageable에 Sort를 주면 별칭 없는 id로 나가서 모호해진다.
        Page<TopicRepository.CombinationRow> rows = topicRepository.findCombinations(
                topicId,
                sourceId,
                active,
                PageRequest.of(page, size)
        );

        return TopicSourceResDTO.CombinationPage.from(PageResponse.of(
                rows.getContent().stream().map(TopicConverter::toCombination).toList(),
                page,
                size,
                rows.getTotalElements()
        ));
    }

    /**
     * 없는 주제를 필터로 주면 빈 목록 대신 TOPIC404를 낸다. 필터가 잘못된 것과 조합이 없는 것을 구분하기 위해서다.
     * sourceId는 명세에 실패 응답이 없어 그대로 필터로만 쓴다.
     */
    private void validateTopicFilter(Long topicId) {
        if (topicId != null && !topicRepository.existsById(topicId)) {
            throw new TopicException(TopicErrorCode.TOPIC_NOT_FOUND);
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
