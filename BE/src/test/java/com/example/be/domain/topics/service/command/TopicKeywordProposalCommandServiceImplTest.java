package com.example.be.domain.topics.service.command;

import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordBucket;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordChangeAction;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopicKeywordProposalCommandServiceImplTest {

    private final TopicKeywordProposalRepository proposalRepository = mock(TopicKeywordProposalRepository.class);
    private final TopicRepository topicRepository = mock(TopicRepository.class);
    private final TopicKeywordProposalCommandServiceImpl service =
            new TopicKeywordProposalCommandServiceImpl(proposalRepository, topicRepository);

    @Test
    void approveAppliesProposedChangesAndRecordsOnlyActualDelta() {
        Topic topic = topic("SK하이닉스");
        var proposal = proposal(topic, TopicKeywordProposalStatus.PENDING, add("SK하이닉스"), add("HBM4"));
        loaded(proposal);

        var response = service.approve(1L);

        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4");
        assertThat(proposal.getAppliedChanges()).hasSize(1);
        assertThat(proposal.getAppliedChanges().getFirst().before()).isNull();
        assertThat(proposal.getAppliedChanges().getFirst().after()).isEqualTo("HBM4");
        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getCurrentKeywords().getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4");
    }

    @Test
    void rejectPendingKeepsCurrentKeywords() {
        var proposal = proposal(topic("SK하이닉스"), TopicKeywordProposalStatus.PENDING, add("HBM4"));
        loaded(proposal);
        var response = service.reject(1L);
        assertThat(response.getCurrentKeywords().getOptionalKeywords()).containsExactly("SK하이닉스");
        assertThat(proposal.getStatus()).isEqualTo(TopicKeywordProposalStatus.REJECTED);
    }

    @Test
    void rejectedProposalCanBeApprovedAgainstCurrentKeywordsAndReversedAgain() {
        Topic topic = topic("SK하이닉스");
        var proposal = proposal(topic, TopicKeywordProposalStatus.REJECTED, add("HBM4"));
        loaded(proposal);
        replaceOptional(topic, "SK하이닉스", "새 수동 키워드");

        service.approve(1L);
        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "새 수동 키워드", "HBM4");
        service.reject(1L);
        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "새 수동 키워드");
        service.approve(1L);
        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "새 수동 키워드", "HBM4");
        assertThat(proposal.getAppliedChanges()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void sameStateRetryKeepsReviewTimeAndDoesNotReapplyKeywords(String state) {
        Topic topic = topic("SK하이닉스");
        var proposal = proposal(topic, TopicKeywordProposalStatus.valueOf(state), add("HBM4"));
        loaded(proposal);
        var reviewedAt = proposal.getReviewedAt();
        if (state.equals("APPROVED")) service.approve(1L);
        else service.reject(1L);
        assertThat(proposal.getReviewedAt()).isEqualTo(reviewedAt);
        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스");
        assertThat(topic.getKeywordRevisions()).isEmpty();
    }

    @Test
    void rejectsPendingApprovalWhenBaselineIsStaleWithoutChangingState() {
        Topic topic = topic("SK하이닉스");
        var proposal = proposal(topic, TopicKeywordProposalStatus.PENDING, add("HBM4"));
        loaded(proposal);
        replaceOptional(topic, "이후 변경");
        assertThatThrownBy(() -> service.approve(1L)).isInstanceOf(TopicException.class)
                .extracting("code").isEqualTo(TopicErrorCode.KEYWORD_PROPOSAL_STALE);
        assertThat(proposal.getStatus()).isEqualTo(TopicKeywordProposalStatus.PENDING);
        assertThat(topic.getOptionalKeywords()).containsExactly("이후 변경");
    }

    @Test
    void legacyReversalReconstructsDeltaWithoutRemovingPreexistingKeywordOrUnrelatedChanges() {
        Topic topic = topic("SK하이닉스");
        var proposal = proposal(topic, TopicKeywordProposalStatus.APPROVED, add("SK하이닉스"), add("HBM4"));
        // An old approval's resulting topic has no revision metadata.
        Topic current = topic("SK하이닉스", "HBM4", "다른 키워드");
        proposal = withCurrentTopic(proposal, current);
        loaded(proposal);

        service.reject(1L);

        assertThat(current.getOptionalKeywords()).containsExactly("SK하이닉스", "다른 키워드");
        assertThat(proposal.getStatus()).isEqualTo(TopicKeywordProposalStatus.REJECTED);
    }

    @Test
    void legacyReversalRestoresOnlyActuallyRemovedKeywordsUsingOriginalSpelling() {
        var original = proposal(topic("hBm4", "SK하이닉스"), TopicKeywordProposalStatus.APPROVED,
                remove("HBM4"), remove("원래 없던 키워드"));
        Topic current = topic("SK하이닉스", "다른 키워드");
        loaded(withCurrentTopic(original, current));
        service.reject(1L);
        assertThat(current.getOptionalKeywords()).containsExactly("SK하이닉스", "다른 키워드", "hBm4");
    }

    @Test
    void legacyReversalPreservesLaterApprovalIntentEvenWhenLaterAdditionWasANoOp() {
        var original = proposal(topic("SK하이닉스"), TopicKeywordProposalStatus.APPROVED, add("HBM4"));
        Topic current = topic("SK하이닉스", "HBM4");
        var proposal = withCurrentTopic(original, current);
        var later = proposal(current, TopicKeywordProposalStatus.APPROVED, add("hbm4"));
        when(proposalRepository.findOtherApprovedByTopicId(7L, 1L)).thenReturn(List.of(later));
        loaded(proposal);
        service.reject(1L);
        assertThat(current.getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4");
    }

    @Test
    void legacyReversalPreservesAffectedValueThatNoLongerMatchesExpectedResult() {
        var original = proposal(topic("SK하이닉스"), TopicKeywordProposalStatus.APPROVED, add("HBM4"));
        Topic current = topic("SK하이닉스", "hbm4");
        loaded(withCurrentTopic(original, current));
        service.reject(1L);
        assertThat(current.getOptionalKeywords()).containsExactly("SK하이닉스", "hbm4");
    }

    @Test
    void legacyReversalPreservesKeywordRemovedAndReaddedAfterRevisionTrackingStarted() {
        var original = proposal(topic("SK하이닉스"), TopicKeywordProposalStatus.APPROVED, add("HBM4"));
        Topic current = topic("SK하이닉스", "HBM4");
        var proposal = withCurrentTopic(original, current);
        loaded(proposal);
        replaceOptional(current, "SK하이닉스");
        replaceOptional(current, "SK하이닉스", "HBM4");
        service.reject(1L);
        assertThat(current.getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4");
    }

    @Test
    void trackedEmptyDeltaNeverFallsBackToLegacyReconstruction() {
        Topic topic = topic("SK하이닉스");
        var proposal = proposal(topic, TopicKeywordProposalStatus.REJECTED, add("HBM4"));
        replaceOptional(topic, "SK하이닉스", "HBM4");
        loaded(proposal);
        service.approve(1L);
        assertThat(proposal.getAppliedChanges()).isEmpty();
        service.reject(1L);
        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4");
    }

    @Test
    void legacyEmptyBackfilledBaselineDoesNotProveThatAnAddChangedAnything() {
        var unknownBaseline = Topic.builder().id(7L).name("HBM").requiredKeywords(List.of())
                .optionalKeywords(List.of()).excludedKeywords(List.of()).build();
        var old = proposal(unknownBaseline, TopicKeywordProposalStatus.APPROVED, add("HBM4"));
        Topic current = topic("HBM4");
        var proposal = withCurrentTopic(old, current);
        loaded(proposal);
        service.reject(1L);
        assertThat(current.getOptionalKeywords()).containsExactly("HBM4");
        assertThat(proposal.getStatus()).isEqualTo(TopicKeywordProposalStatus.REJECTED);
    }

    @Test
    void trackedApprovalFromAnActuallyEmptyTopicCanStillBeReversed() {
        var empty = Topic.builder().id(7L).name("HBM").requiredKeywords(List.of())
                .optionalKeywords(List.of()).excludedKeywords(List.of()).build();
        var proposal = proposal(empty, TopicKeywordProposalStatus.PENDING, add("HBM4"));
        loaded(proposal);
        service.approve(1L);
        service.reject(1L);
        assertThat(empty.getOptionalKeywords()).isEmpty();
        assertThat(proposal.getAppliedChanges()).hasSize(1);
    }

    @Test
    void doesNotLockAnythingForMissingProposal() {
        when(proposalRepository.findTopicIdById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(99L)).isInstanceOf(TopicException.class)
                .extracting("code").isEqualTo(TopicErrorCode.KEYWORD_PROPOSAL_NOT_FOUND);
        verifyNoInteractions(topicRepository);
    }

    @Test
    void obtainsSharedTopicWriteLockBeforeLoadingMutableProposalState() throws Exception {
        var proposal = proposal(topic("SK하이닉스"), TopicKeywordProposalStatus.PENDING, add("HBM4"));
        loaded(proposal);
        service.approve(1L);
        var ordered = inOrder(proposalRepository, topicRepository);
        ordered.verify(proposalRepository).findTopicIdById(1L);
        ordered.verify(topicRepository).lockByIds(List.of(7L));
        ordered.verify(proposalRepository).findWithTopicById(1L);
        assertThat(TopicRepository.class.getMethod("lockByIds", Collection.class).getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentReviewsSerializeBeforeReadingKeywordState(boolean sameProposal) throws Exception {
        Topic topic = topic("SK하이닉스");
        var first = proposal(topic, TopicKeywordProposalStatus.REJECTED, add("HBM4"));
        var second = sameProposal ? first : proposal(topic, TopicKeywordProposalStatus.REJECTED, add("HBM5"));
        when(proposalRepository.findTopicIdById(anyLong())).thenReturn(Optional.of(7L));
        ReentrantLock rowLock = new ReentrantLock();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondArrived = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        when(topicRepository.lockByIds(List.of(7L))).thenAnswer(call -> {
            if (requests.incrementAndGet() == 2) secondArrived.countDown();
            rowLock.lock();
            if (entered.getCount() > 0) {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return List.of(topic);
        });
        when(proposalRepository.findWithTopicById(anyLong())).thenAnswer(call -> {
            assertThat(rowLock.isHeldByCurrentThread()).isTrue();
            return Optional.of(call.<Long>getArgument(0) == 1L ? first : second);
        });
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        doAnswer(call -> { rowLock.unlock(); return null; }).when(transactions).commit(any());
        doAnswer(call -> { rowLock.unlock(); return null; }).when(transactions).rollback(any());
        var proxied = transactional(transactions);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> proxied.approve(1L));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var two = workers.submit(() -> proxied.approve(sameProposal ? 1L : 2L));
            assertThat(secondArrived.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("APPROVED");
            assertThat(two.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("APPROVED");
        } finally {
            release.countDown();
        }
        if (sameProposal) {
            assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4");
            assertThat(topic.getKeywordRevisions()).containsEntry("OPTIONAL:hbm4", 1L);
        } else {
            assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4", "HBM5");
            assertThat(first.getAppliedChanges()).hasSize(1);
            assertThat(second.getAppliedChanges()).hasSize(1);
        }
    }

    @Test
    void keywordAndProposalStatusChangesShareTheSameRollbackBoundary() {
        var proposal = spy(proposal(topic("SK하이닉스"), TopicKeywordProposalStatus.PENDING, add("HBM4")));
        loaded(proposal);
        doThrow(new IllegalStateException("review persistence failed")).when(proposal).approve(any(), anyList());
        var transactions = mock(PlatformTransactionManager.class);
        var transaction = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        assertThatThrownBy(() -> transactional(transactions).approve(1L)).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    private TopicKeywordProposalCommandService transactional(PlatformTransactionManager transactions) {
        var proxy = new ProxyFactory(service);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(advice);
        return (TopicKeywordProposalCommandService) proxy.getProxy();
    }

    private void loaded(TopicKeywordProposal proposal) {
        Topic topic = proposal.getTopic();
        when(proposalRepository.findTopicIdById(1L)).thenReturn(Optional.of(7L));
        when(topicRepository.lockByIds(List.of(7L))).thenReturn(List.of(topic));
        when(proposalRepository.findWithTopicById(1L)).thenReturn(Optional.of(proposal));
    }

    private Topic topic(String... optional) {
        return Topic.builder().id(7L).name("HBM").requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of(optional)).excludedKeywords(List.of("광고")).build();
    }

    private TopicKeywordProposal proposal(Topic topic, TopicKeywordProposalStatus status, TopicKeywordChange... changes) {
        return TopicKeywordProposal.builder().id(1L).topic(topic).collectionRunId(42L)
                .idempotencyKey("run:42:topic:7:keyword-strategy").summary("키워드 제안")
                .changes(List.of(changes)).baselineRequiredKeywords(topic.getRequiredKeywords())
                .baselineOptionalKeywords(topic.getOptionalKeywords()).baselineExcludedKeywords(topic.getExcludedKeywords())
                .status(status).createdAt(LocalDateTime.of(2026, 9, 3, 10, 15))
                .reviewedAt(status == TopicKeywordProposalStatus.PENDING ? null : LocalDateTime.of(2026, 9, 3, 11, 15))
                .build();
    }

    private TopicKeywordProposal withCurrentTopic(TopicKeywordProposal old, Topic current) {
        return TopicKeywordProposal.builder().id(old.getId()).topic(current).collectionRunId(old.getCollectionRunId())
                .idempotencyKey(old.getIdempotencyKey()).summary(old.getSummary()).changes(old.getChanges())
                .baselineRequiredKeywords(old.getBaselineRequiredKeywords()).baselineOptionalKeywords(old.getBaselineOptionalKeywords())
                .baselineExcludedKeywords(old.getBaselineExcludedKeywords()).status(old.getStatus())
                .createdAt(old.getCreatedAt()).reviewedAt(old.getReviewedAt()).build();
    }

    private TopicKeywordChange add(String keyword) {
        return new TopicKeywordChange(TopicKeywordBucket.OPTIONAL, TopicKeywordChangeAction.ADD, keyword, "추가 제안");
    }

    private TopicKeywordChange remove(String keyword) {
        return new TopicKeywordChange(TopicKeywordBucket.OPTIONAL, TopicKeywordChangeAction.REMOVE, keyword, "제거 제안");
    }

    private void replaceOptional(Topic topic, String... keywords) {
        topic.update(topic.getName(), topic.getQueryText(), topic.getRequiredKeywords(), List.of(keywords),
                topic.getExcludedKeywords(), topic.getBatchSize(), topic.getIntervalMinutes(), topic.isActive());
    }
}
