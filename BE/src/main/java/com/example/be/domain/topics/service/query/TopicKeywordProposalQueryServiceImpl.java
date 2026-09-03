package com.example.be.domain.topics.service.query;

import com.example.be.domain.topics.converter.TopicKeywordProposalConverter;
import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TopicKeywordProposalQueryServiceImpl implements TopicKeywordProposalQueryService {

    private final TopicKeywordProposalRepository proposalRepository;

    @Override
    public PageResponse<TopicKeywordProposalResDTO.Item> getKeywordProposals(String status, int page, int size) {
        validatePaging(page, size);
        Page<TopicKeywordProposal> proposals = proposalRepository.findPageByStatus(
                parseStatus(status),
                PageRequest.of(page, size));

        return PageResponse.of(
                proposals.getContent().stream()
                        .map(TopicKeywordProposalConverter::toItem)
                        .toList(),
                page,
                size,
                proposals.getTotalElements());
    }

    private TopicKeywordProposalStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return TopicKeywordProposalStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new GeneralException(
                    GeneralErrorCode.BAD_REQUEST,
                    "status는 PENDING / APPROVED / REJECTED 중 하나여야 합니다.");
        }
    }

    private void validatePaging(int page, int size) {
        if (page < PageResponse.DEFAULT_PAGE) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (size < PageResponse.MIN_SIZE || size > PageResponse.MAX_SIZE) {
            throw new GeneralException(
                    GeneralErrorCode.BAD_REQUEST,
                    "size는 " + PageResponse.MIN_SIZE + " 이상 " + PageResponse.MAX_SIZE + " 이하여야 합니다.");
        }
    }
}
