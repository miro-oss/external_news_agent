package com.example.be.domain.collection.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 수집 실행의 동시 실행 상한과 주제별 순서를 같은 DB 잠금 안에서 확정한다. */
@Entity
@Table(name = "news_collection_dispatch_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionDispatchLock {
    @Id
    private Long id;
}
