package com.example.be.domain.issues.service;

import com.example.be.domain.issues.dto.res.IssueResDTO;

public interface IssueQueryService {

    IssueResDTO.Detail getIssue(Long issueId);
}
