package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicSubjectAliasesTest {
    private final DeterministicEntityExtractor extractor = new DeterministicEntityExtractor();

    @Test
    void recognizesTransliteratedCompanyNamesWithoutMatchingLongerNames() {
        for (String name : List.of("후지쓰", "후지쯔", "Fujitsu")) {
            assertEquals(Set.of("후지쓰"), extractor.extractTitleOrganizations(name + "는 신제품을 수출한다"));
            assertEquals("후지쓰", DeterministicEntityExtractor.canonicalSubject(name));
        }
        assertEquals(Set.of(), extractor.extractTitleOrganizations("후지쓰산업 FujitsuLabs 신제품"));
    }

    @Test
    void generalUniversitySuffixesIdentifyDifferentInstitutionsWithoutAPositiveVote() {
        assertEquals(Set.of("새빛대"), extractor.extractTitleOrganizations("새빛대학교, 2028학년도 수시 경쟁률 발표"));
        assertEquals(Set.of("새빛대"), extractor.extractTitleOrganizations("새빛대 수시 경쟁률 9.5대 1"));
        assertEquals(Set.of("가온대"), extractor.extractTitleOrganizations("가온대 2028학년도 경쟁률 발표"));
        assertEquals(Set.of(), extractor.extractOrganizations("새빛대 수시 경쟁률 발표", null));
        assertTrue(DeterministicEntityExtractor.mentionsSubject("새빛대학교는 발표했다", "새빛대"));
        assertFalse(DeterministicEntityExtractor.mentionsSubject("새빛대학교산업은 발표했다", "새빛대"));
    }

    @Test
    void explicitInstitutionAliasesRemainTheSameSubject() {
        assertEquals(Set.of("한국에너지공과대"), extractor.extractTitleOrganizations("에너지공대 2028학년도 경쟁률 발표"));
        assertEquals(Set.of("한국에너지공과대"), extractor.extractTitleOrganizations("켄텍 수시모집 결과 발표"));
        assertEquals(Set.of("한국기술교육대"), extractor.extractTitleOrganizations("한기대, 학생 실습 프로그램 운영"));
        assertEquals("한국기술교육대", DeterministicEntityExtractor.canonicalSubject("KOREATECH"));
    }

    @Test
    void genericAcademicWordsAndLongerOrganizationNamesAreNotInstitutions() {
        assertEquals(Set.of(), extractor.extractTitleOrganizations("차세대 반도체 교육 확대…전문대학 협력"));
        assertEquals(Set.of(), extractor.extractTitleOrganizations("새빛대산업 학생 취업 지원"));
        assertTrue(DeterministicEntityExtractor.mentionsSubject("Fujitsu는 발표했다", "후지쓰"));
        assertFalse(DeterministicEntityExtractor.mentionsSubject("FujitsuLabs는 발표했다", "후지쓰"));
    }
}
