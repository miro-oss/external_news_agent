package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔티티는 §5-2 3단계에서 <b>"교집합 ≥ 2 AND 48h"</b>로 이슈를 묶는 데 쓰이고,
 * {@code IssueClusterer}가 그 결과를 전이적으로 union 한다. 그래서 사건을 못 가르는 값이
 * 엔티티에 섞이면 관계없는 기사가 통째로 한 이슈가 된다 (#118).
 *
 * <p>아래 본문 fixture는 run 3859에서 실제로 관측된 노이즈 형태를 그대로 옮긴 것이다.
 * 골든 클러스터셋(`clusters.v1`)은 합성 원고라 이런 게 없었고, 그래서 홀드아웃 precision
 * 1.000을 받고도 실수집에서 90%가 한 덩어리로 묶였다.
 */
class DeterministicEntityExtractorTest {

    private final DeterministicEntityExtractor extractor = new DeterministicEntityExtractor();

    /** 실제 한국어 기사 본문 끝에 흔히 붙는 것들. 기자 바이라인·매체 약어·광고 문구. */
    private static final String NOISY_BODY = """
            업계에 따르면 이번 투자로 생산능력이 늘어난다고 IT 업계 CEO들은 전망했다.
            ADVERTISEMENT
            사진=NEWSIS 제공. 영상 제공 NEWS1.
            홍길동 기자 hong1987@example.com
            김철수 기자 judy6956@example.com
            저작권자 (c) NEWSIS. 무단전재-재배포 금지.
            """;

    @Test
    @DisplayName("본문의 기자 ID·매체 약어·광고 문구는 엔티티가 되지 않는다")
    void ignoresBylineAndBoilerplateInBody() {
        Set<String> entities = extractor.extract(
                "SK하이닉스, HBM4 12단 양산 라인 증설",
                "청주 M15X에 신규 라인을 깐다.",
                NOISY_BODY,
                List.of());

        assertFalse(entities.contains("HONG1987"), "기자 이메일 ID가 엔티티로 들어갔다");
        assertFalse(entities.contains("JUDY6956"), "기자 이메일 ID가 엔티티로 들어갔다");
        assertFalse(entities.contains("ADVERTISEMENT"));
        assertFalse(entities.contains("NEWSIS"));
        assertFalse(entities.contains("NEWS1"));
        assertFalse(entities.contains("CEO"));
        assertFalse(entities.contains("IT"));
    }

    @Test
    @DisplayName("제목·요약의 제품명과 회사명은 그대로 남는다")
    void keepsProductAndCompanyAnchorsFromTitleAndSummary() {
        Set<String> entities = extractor.extract(
                "SK하이닉스, HBM4 12단 양산 라인 증설",
                "청주 M15X에 신규 라인을 깔고 DDR5 물량도 늘린다.",
                NOISY_BODY,
                List.of());

        assertTrue(entities.contains("HBM4"), "제품명이 빠졌다");
        assertTrue(entities.contains("M15X"), "팹 이름이 빠졌다");
        assertTrue(entities.contains("DDR5"), "제품명이 빠졌다");
        assertTrue(entities.contains("SK하이닉스"), "회사 사전 항목이 빠졌다");
    }

    @Test
    @DisplayName("숫자로 끝나는 제품 코드는 기자 이메일 ID로 오인하지 않는다")
    void keepsProductCodesEndingInDigits() {
        Set<String> entities = extractor.extract(
                "엔비디아 H100·RTX5090 공급 확대",
                "차세대 데이터센터와 그래픽카드 물량을 늘린다.",
                "",
                List.of());

        assertTrue(entities.contains("H100"));
        assertTrue(entities.contains("RTX5090"));
    }

    @Test
    @DisplayName("본문 배경 조직은 엔티티 간선을 우회하지 않고 주제 키워드만 본문까지 훑는다")
    void excludesBodyOnlyOrganizationsFromEntities() {
        Set<String> entities = extractor.extract(
                "반도체 장비 수요 회복",
                "하반기 전망이 개선됐다.",
                "삼성전자와 마이크론이 증설을 검토 중이라고 업계는 전했다." + NOISY_BODY,
                List.of("증설"));

        assertFalse(entities.contains("삼성전자"));
        assertFalse(entities.contains("마이크론"));
        assertTrue(entities.contains("증설"), "주제 키워드가 본문에서 잡히지 않았다");
    }

    @Test
    @DisplayName("관계없는 기사 둘이 엔티티를 2개 이상 공유하지 않는다")
    void unrelatedArticlesDoNotShareEnoughEntitiesToMerge() {
        Set<String> semiconductor = extractor.extract(
                "SK하이닉스, HBM4 12단 양산 라인 증설",
                "청주 M15X에 신규 라인을 깐다.",
                NOISY_BODY,
                List.of());
        Set<String> unrelated = extractor.extract(
                "롯데, 본원적 경쟁력 강화 통했다…주력 계열사 영업익 일제 반등",
                "3분기 실적이 개선됐다.",
                NOISY_BODY,
                List.of());

        long shared = semiconductor.stream().filter(unrelated::contains).count();
        assertTrue(shared < 2,
                "관계없는 기사가 엔티티 %d개를 공유한다 — 교집합 2 규칙으로 병합된다: %s"
                        .formatted(shared, semiconductor.stream().filter(unrelated::contains).toList()));
    }

    @Test
    @DisplayName("보일러플레이트가 아무리 많아도 기사당 앵커 수에 상한이 있다")
    void capsAnchorsPerArticle() {
        StringBuilder summary = new StringBuilder();
        for (int index = 0; index < 100; index++) {
            summary.append("ANCHOR").append((char) ('A' + index % 26)).append("X ");
        }
        Set<String> entities = extractor.extract("제목", summary.toString(), "", List.of());

        // 상한 상수를 직접 참조하지 않는다. 구현 전 코드에서도 이 테스트가 컴파일돼야
        // "고치기 전에는 실패한다"를 실제로 보일 수 있다.
        assertTrue(entities.size() <= 20, "앵커 상한이 걸리지 않았다: " + entities.size());
    }

    @Test
    @DisplayName("두 글자 약어는 사건을 못 가르므로 제외한다")
    void dropsTwoLetterAnchors() {
        Set<String> entities = extractor.extract(
                "AP 통신, TV 광고 시장 분석",
                "PC 수요는 회복 중이다.",
                "",
                List.of());

        assertEquals(Set.of(), entities);
    }

    @Test
    @DisplayName("제목·요약 조직 추출은 약칭을 정규화하고 제품코드는 제외한다")
    void extractsOrganizationsWithoutTechnicalAnchors() {
        Set<String> organizations = extractor.extractOrganizations(
                "SK하닉·AMAT·DGIST 공동 발표",
                "LG전자와 KB운용도 참여하고 HBM4를 공개했다.");

        assertEquals(Set.of(
                "SK하이닉스", "어플라이드머티어리얼즈", "DGIST", "LG전자", "KB자산운용"),
                organizations);
        assertFalse(organizations.contains("HBM4"));
    }

    @Test
    @DisplayName("짧은 영문 조직 약칭은 다른 영단어 내부에서 매칭하지 않는다")
    void requiresWordBoundariesForShortAsciiAliases() {
        Set<String> organizations = extractor.extractOrganizations(
                "Dramatic industry outlook", null);

        assertFalse(organizations.contains("어플라이드머티어리얼즈"));
    }

    @Test
    @DisplayName("한글 조직 별칭은 일반 단어의 접두사로 등장하면 매칭하지 않는다")
    void rejectsKoreanAliasPrefixesInsideCommonWords() {
        Set<String> organizations = extractor.extractOrganizations(
                "AI 인텔리전스 애플리케이션과 메타버스 전략", null);

        assertEquals(Set.of(), organizations);
    }

    @Test
    @DisplayName("한글 조직 별칭 뒤에 붙은 조사는 허용한다")
    void acceptsKoreanPostpositionsAfterAliases() {
        Set<String> organizations = extractor.extractOrganizations(
                "인텔은 애플과 메타의 공동 발표", null);

        assertEquals(Set.of("인텔", "애플", "메타"), organizations);
    }

    @Test
    @DisplayName("일반 명사구 미래산업은 법인 표기가 있을 때만 조직으로 본다")
    void requiresCorporateMarkerForAmbiguousOrganizationName() {
        assertEquals(Set.of(), extractor.extractOrganizations("반도체 미래산업 육성 전략", null));
        assertEquals(Set.of("미래산업"), extractor.extractOrganizations("미래산업(주), 장비 공급", null));
    }
}
