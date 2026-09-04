package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Title organizations provide negative evidence without broadening positive organization edges. */
class DeterministicTitleOrganizationTest {

    private final DeterministicEntityExtractor extractor = new DeterministicEntityExtractor();

    @Test
    void recognizesVendorNamesAndTheirEnglishAliases() {
        Set<String> expected = Set.of("한화세미텍", "디엠에스", "한미반도체");

        assertEquals(expected,
                extractor.extractTitleOrganizations("한화세미텍·디엠에스·한미반도체 장비 공개"));
        assertEquals(expected,
                extractor.extractTitleOrganizations("Hanwha Semitech, DMS, Hanmi Semiconductor present equipment"));
    }

    @Test
    void acceptsKoreanPostpositionsAfterVendorNames() {
        assertEquals(Set.of("한화세미텍", "디엠에스", "한미반도체"),
                extractor.extractTitleOrganizations("한화세미텍은 디엠에스와 한미반도체의 장비를 비교했다"));
    }

    @Test
    void doesNotMatchVendorAliasesInsideLongerWords() {
        assertEquals(Set.of(), extractor.extractTitleOrganizations(
                "한화세미텍산업 한미반도체산업 디엠에스텍 DMSensor XDMS Hanwha Semitechnology"));
    }

    @Test
    void preservesKnownOrganizationAliasesAndIgnoresMissingTitles() {
        assertEquals(Set.of("삼성전자", "SK하이닉스", "인텔"),
                extractor.extractTitleOrganizations("Samsung Electronics, SK hynix, Intel 공동 발표"));
        assertEquals(Set.of(), extractor.extractTitleOrganizations(null));
        assertEquals(Set.of(), extractor.extractTitleOrganizations(" "));
    }

    @Test
    void vendorConflictAliasesDoNotCreateNewPositiveOrganizationEdges() {
        String title = "한화세미텍 디엠에스 한미반도체 장비 공개";

        assertEquals(Set.of(), extractor.extractOrganizations(title, null));
        assertEquals(Set.of(), extractor.extractWithOrganizations(title, null, null, List.of())
                .organizations());
    }
}
