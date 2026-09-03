package com.example.be.domain.topics.service.command;

import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;

public interface TopicKeywordProposalCommandService {

    TopicKeywordProposalResDTO.Item approve(Long proposalId);

    TopicKeywordProposalResDTO.Item reject(Long proposalId);
}
