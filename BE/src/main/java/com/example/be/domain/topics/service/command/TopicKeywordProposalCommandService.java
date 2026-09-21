package com.example.be.domain.topics.service.command;

import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;

import java.util.List;

public interface TopicKeywordProposalCommandService {

    TopicKeywordProposalResDTO.Item approve(Long proposalId);

    TopicKeywordProposalResDTO.Item approve(Long proposalId, List<Integer> selectedChangeIndexes);

    TopicKeywordProposalResDTO.Item reject(Long proposalId);
}
