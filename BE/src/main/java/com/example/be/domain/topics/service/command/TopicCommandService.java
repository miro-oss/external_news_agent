package com.example.be.domain.topics.service.command;

import com.example.be.domain.topics.dto.req.TopicReqDTO;
import com.example.be.domain.topics.dto.res.TopicResDTO;


public interface TopicCommandService {

    TopicResDTO.Created createTopic(TopicReqDTO.Create request);

    TopicResDTO.Updated updateTopic(Long topicId, TopicReqDTO.Update request);

    TopicResDTO.Activated updateActivation(Long topicId, TopicReqDTO.Activation request);

    TopicResDTO.SourcesLinked replaceSources(Long topicId, TopicReqDTO.SourceLink request);

    TopicResDTO.Deleted deleteTopic(Long topicId);
}
