package com.example.be.domain.topics.service.command;

import com.example.be.domain.topics.converter.TopicKeywordProposalConverter;
import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordAppliedChange;
import com.example.be.domain.topics.entity.TopicKeywordBucket;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.entity.TopicKeywordReviewState;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Transactional
public class TopicKeywordProposalCommandServiceImpl implements TopicKeywordProposalCommandService {

    private final TopicKeywordProposalRepository proposalRepository;
    private final TopicRepository topicRepository;

    @Override
    public TopicKeywordProposalResDTO.Item approve(Long proposalId) {
        TopicKeywordProposal proposal = getLockedProposal(proposalId);
        if (proposal.getStatus() == TopicKeywordProposalStatus.APPROVED) {
            return TopicKeywordProposalConverter.toItem(proposal);
        }
        if (proposal.isPending() && !proposal.matchesCurrentTopicKeywords()) {
            throw new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_STALE);
        }
        var applied = proposal.getTopic().applyKeywordChanges(proposal.getChanges());
        proposal.approve(LocalDateTime.now(ApiTimeZone.ZONE), applied);
        return TopicKeywordProposalConverter.toItem(proposal);
    }

    @Override
    public TopicKeywordProposalResDTO.Item reject(Long proposalId) {
        TopicKeywordProposal proposal = getLockedProposal(proposalId);
        if (proposal.getStatus() == TopicKeywordProposalStatus.REJECTED) {
            return TopicKeywordProposalConverter.toItem(proposal);
        }
        if (proposal.getStatus() == TopicKeywordProposalStatus.APPROVED) {
            List<TopicKeywordAppliedChange> applied = proposal.getAppliedChanges() == null
                    ? legacyAppliedChanges(proposal) : proposal.getAppliedChanges();
            proposal.getTopic().reverseKeywordChanges(applied);
        }
        proposal.reject(LocalDateTime.now(ApiTimeZone.ZONE));
        return TopicKeywordProposalConverter.toItem(proposal);
    }

    private TopicKeywordProposal getLockedProposal(Long proposalId) {
        Long topicId = proposalRepository.findTopicIdById(proposalId)
                .orElseThrow(() -> new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_NOT_FOUND));
        // Always lock the topic first, before loading mutable topic/proposal state into this transaction.
        // Manual topic edits and collection creation use the same topic lock.
        if (topicRepository.lockByIds(List.of(topicId)).isEmpty()) {
            throw new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_NOT_FOUND);
        }
        return proposalRepository.findWithTopicById(proposalId)
                .orElseThrow(() -> new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_NOT_FOUND));
    }

    private List<TopicKeywordAppliedChange> legacyAppliedChanges(TopicKeywordProposal proposal) {
        var baseline = new TopicKeywordReviewState(proposal.getBaselineRequiredKeywords(),
                proposal.getBaselineOptionalKeywords(), proposal.getBaselineExcludedKeywords(), Map.of());
        // V37 backfilled older rows with three empty arrays, not with their actual pre-approval keywords.
        // An empty legacy baseline therefore cannot prove that a proposed ADD originally added anything.
        if (Arrays.stream(TopicKeywordBucket.values()).allMatch(bucket -> baseline.keywords(bucket).isEmpty())) {
            return List.of();
        }
        var laterApprovals = proposalRepository.findOtherApprovedByTopicId(proposal.getTopic().getId(), proposal.getId())
                .stream().filter(other -> other.getReviewedAt() == null || proposal.getReviewedAt() == null
                        || !other.getReviewedAt().isBefore(proposal.getReviewedAt())).toList();
        return baseline.apply(proposal.getChanges()).stream()
                // Existing ADDs and absent REMOVEs never appear in this reconstructed actual delta.
                .filter(change -> laterApprovals.stream().noneMatch(other -> other.getChanges().stream()
                        .anyMatch(intent -> intent.bucket() == change.bucket()
                                && TopicKeywordReviewState.normalize(intent.keyword()).equals(change.keyword()))))
                // A legacy change has no revision ownership. Any tracked later edit protects that keyword.
                .map(change -> new TopicKeywordAppliedChange(change.bucket(), change.keyword(),
                        change.before(), change.after(), 0L))
                .toList();
    }
}
