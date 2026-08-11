package com.example.be.domain.topics.repository;

import com.example.be.domain.topics.entity.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TopicRepository extends JpaRepository<Topic, Long>, JpaSpecificationExecutor<Topic> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * 목록 조회에서 주제마다 소스 컬렉션을 지연 로딩하지 않도록 연결 수만 한 번에 센다.
     */
    @Query("SELECT t.id AS topicId, SIZE(t.sources) AS linkedSourceCount FROM Topic t WHERE t.id IN :topicIds")
    List<LinkedSourceCount> countLinkedSources(@Param("topicIds") Collection<Long> topicIds);

    interface LinkedSourceCount {

        Long getTopicId();

        int getLinkedSourceCount();
    }
}
