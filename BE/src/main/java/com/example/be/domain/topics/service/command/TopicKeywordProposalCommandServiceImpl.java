package com.example.be.domain.topics.service.command;

import com.example.be.domain.topics.converter.TopicKeywordProposalConverter;
import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class TopicKeywordProposalCommandServiceImpl implements TopicKeywordProposalCommandService {

    private final TopicKeywordProposalRepository proposalRepository;

    @Override
    public TopicKeywordProposalResDTO.Item approve(Long proposalId) {
        TopicKeywordProposal proposal = getPendingProposal(proposalId);
        if (!proposal.matchesCurrentTopicKeywords()) {
            throw new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_STALE);
        }
        proposal.getTopic().applyKeywordChanges(proposal.getChanges());
        proposal.approve(LocalDateTime.now(ApiTimeZone.ZONE));
        return TopicKeywordProposalConverter.toItem(proposal);
    }

    @Override
    public TopicKeywordProposalResDTO.Item reject(Long proposalId) {
        TopicKeywordProposal proposal = getPendingProposal(proposalId);
        proposal.reject(LocalDateTime.now(ApiTimeZone.ZONE));
        return TopicKeywordProposalConverter.toItem(proposal);
    }

    private TopicKeywordProposal getPendingProposal(Long proposalId) {
        TopicKeywordProposal proposal = proposalRepository.findWithTopicById(proposalId)
                .orElseThrow(() -> new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_NOT_FOUND));
        if (!proposal.isPending()) {
            throw new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_ALREADY_REVIEWED);
        }
        return proposal;
    }
}
