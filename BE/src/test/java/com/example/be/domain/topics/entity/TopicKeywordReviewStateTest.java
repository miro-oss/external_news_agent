package com.example.be.domain.topics.entity;

import com.example.be.domain.topics.converter.TopicKeywordAppliedChangeListConverter;
import com.example.be.domain.topics.converter.TopicKeywordRevisionMapConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopicKeywordReviewStateTest {

    @ParameterizedTest
    @EnumSource(TopicKeywordBucket.class)
    void additionReversalPreservesPreexistingAndUnrelatedKeywordsInEveryBucket(TopicKeywordBucket bucket) {
        var state = state();
        var changes = state.apply(List.of(add(bucket, "hbm"), add(bucket, "HBM4")));
        state.apply(List.of(add(bucket, "HBM5")));
        state.reverse(changes);
        assertThat(changes).hasSize(1);
        assertThat(state.keywords(bucket)).containsExactly("HBM", "HBM5");
    }

    @ParameterizedTest
    @EnumSource(TopicKeywordBucket.class)
    void removalReversalRestoresOnlyTheOriginalValueAndBucket(TopicKeywordBucket bucket) {
        var state = state();
        var changes = state.apply(List.of(remove(bucket, "hbm"), remove(bucket, "원래 없음")));
        state.apply(List.of(add(bucket, "HBM5")));
        state.reverse(changes);
        assertThat(changes).hasSize(1);
        assertThat(state.keywords(bucket)).containsExactly("HBM5", "HBM");
    }

    @Test
    void anotherApprovalAdoptingAnExistingKeywordProtectsItEvenAfterBothAreRejected() {
        var state = state();
        var first = state.apply(List.of(add(TopicKeywordBucket.OPTIONAL, "HBM4")));
        var second = state.apply(List.of(add(TopicKeywordBucket.OPTIONAL, "hbm4")));
        assertThat(second).isEmpty();
        state.reverse(first);
        state.reverse(second);
        // The second approval added nothing itself; undoing it must not remove another decision's keyword.
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("HBM", "HBM4");
    }

    @Test
    void anotherApprovalAdoptingAnAbsentKeywordRemovalProtectsItsAbsence() {
        var state = state();
        var first = state.apply(List.of(remove(TopicKeywordBucket.OPTIONAL, "HBM")));
        var second = state.apply(List.of(remove(TopicKeywordBucket.OPTIONAL, "hbm")));
        state.reverse(first);
        state.reverse(second);
        assertThat(second).isEmpty();
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).isEmpty();
    }

    @Test
    void manualRemovalAndReadditionOfSameValueIsNotUndoneByTheEarlierApproval() {
        var state = state();
        var changes = state.apply(List.of(add(TopicKeywordBucket.OPTIONAL, "HBM4")));
        state.replace(TopicKeywordBucket.OPTIONAL, List.of("HBM"));
        state.replace(TopicKeywordBucket.OPTIONAL, List.of("HBM", "HBM4"));
        state.reverse(changes);
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("HBM", "HBM4");
    }

    @Test
    void manualUnrelatedEditDoesNotPreventReversalOfTheApproval() {
        var state = state();
        var changes = state.apply(List.of(add(TopicKeywordBucket.OPTIONAL, "HBM4")));
        state.replace(TopicKeywordBucket.OPTIONAL, List.of("HBM", "HBM4", "수동 추가"));
        state.replace(TopicKeywordBucket.REQUIRED, List.of("수정한 필수 키워드"));
        state.reverse(changes);
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("HBM", "수동 추가");
        assertThat(state.keywords(TopicKeywordBucket.REQUIRED)).containsExactly("수정한 필수 키워드");
    }

    @Test
    void sameWordInAnotherBucketDoesNotClaimTheOriginalBucket() {
        var state = state();
        var first = state.apply(List.of(add(TopicKeywordBucket.OPTIONAL, "HBM4")));
        state.apply(List.of(add(TopicKeywordBucket.REQUIRED, "HBM4")));
        state.reverse(first);
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("HBM");
        assertThat(state.keywords(TopicKeywordBucket.REQUIRED)).containsExactly("HBM", "HBM4");
    }

    @Test
    void opposingActionsWithinOneApprovalRecordNetDeltaAndCanRestoreSpelling() {
        var state = state();
        var noNetChange = state.apply(List.of(add(TopicKeywordBucket.OPTIONAL, "HBM4"), remove(TopicKeywordBucket.OPTIONAL, "hbm4")));
        assertThat(noNetChange).isEmpty();
        var spelling = state.apply(List.of(remove(TopicKeywordBucket.OPTIONAL, "HBM"), add(TopicKeywordBucket.OPTIONAL, "hbm")));
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("hbm");
        state.reverse(spelling);
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("HBM");
    }

    @Test
    void reversingOneApprovalTwiceDoesNotReapplyTheOldInverse() {
        var state = state();
        var changes = state.apply(List.of(remove(TopicKeywordBucket.OPTIONAL, "HBM")));
        state.reverse(changes);
        var revisions = state.revisions();
        state.reverse(changes);
        assertThat(state.revisions()).isEqualTo(revisions);
        assertThat(state.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("HBM");
    }

    @Test
    void serializedRevisionAndDeltaKeepOwnershipAcrossReloadAndPreserveLegacyNull() {
        var state = state();
        var changes = state.apply(List.of(add(TopicKeywordBucket.OPTIONAL, "HBM4")));
        var changeConverter = new TopicKeywordAppliedChangeListConverter();
        var revisionConverter = new TopicKeywordRevisionMapConverter();
        var reloaded = new TopicKeywordReviewState(state.keywords(TopicKeywordBucket.REQUIRED),
                state.keywords(TopicKeywordBucket.OPTIONAL), state.keywords(TopicKeywordBucket.EXCLUDED),
                revisionConverter.convertToEntityAttribute(revisionConverter.convertToDatabaseColumn(state.revisions())));
        reloaded.reverse(changeConverter.convertToEntityAttribute(changeConverter.convertToDatabaseColumn(changes)));
        assertThat(reloaded.keywords(TopicKeywordBucket.OPTIONAL)).containsExactly("HBM");
        assertThat(changeConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(changeConverter.convertToEntityAttribute(null)).isNull();
        assertThat(changeConverter.convertToDatabaseColumn(List.of())).isEqualTo("[]");
        assertThat(changeConverter.convertToEntityAttribute("[]")).isEmpty();
    }

    private TopicKeywordReviewState state() {
        return new TopicKeywordReviewState(List.of("HBM"), List.of("HBM"), List.of("HBM"), Map.of());
    }

    private TopicKeywordChange add(TopicKeywordBucket bucket, String keyword) {
        return new TopicKeywordChange(bucket, TopicKeywordChangeAction.ADD, keyword, "추가 제안");
    }

    private TopicKeywordChange remove(TopicKeywordBucket bucket, String keyword) {
        return new TopicKeywordChange(bucket, TopicKeywordChangeAction.REMOVE, keyword, "제거 제안");
    }
}
