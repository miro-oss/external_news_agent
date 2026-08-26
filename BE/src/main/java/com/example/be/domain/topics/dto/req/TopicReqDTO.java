package com.example.be.domain.topics.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// 만들 때 DTO 컨테이너 패턴으로 - 중첩 정적 클래스 패턴
public class TopicReqDTO {

    private TopicReqDTO() {
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "TopicCreateRequest", description = "수집 주제 등록 요청")
    public static class Create {

        @Schema(description = "주제명. 중복 불가. 최대 200자", example = "HBM", requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;

        @Schema(description = "SEARCH 소스에 넘길 검색어. SEARCH 소스를 연결하면 필수. 최대 500자", example = "HBM 반도체")
        private String queryText;

        @Schema(description = "AND 필터. 모두 포함된 기사만 통과", example = "[\"HBM\"]")
        private List<String> requiredKeywords;

        @Schema(description = "OR 필터. 하나라도 포함되면 통과", example = "[\"SK하이닉스\", \"삼성전자\", \"마이크론\"]")
        private List<String> optionalKeywords;

        @Schema(description = "NOT 필터. 하나라도 포함되면 제외", example = "[\"광고\", \"채용\"]")
        private List<String> excludedKeywords;

        @Schema(description = "1회 수집 건수. SEARCH 소스 한 곳당 요청 건수이며 기본값은 20", example = "20")
        private Integer batchSize;

        @Schema(description = "자동 수집 주기(분). 기본값 60", example = "60")
        private Integer intervalMinutes;

        @Schema(description = "활성 여부. 기본값 true", example = "true")
        private Boolean active;

        @Schema(description = "동시에 연결할 소스 ID 목록. 소스 목록 조회로 확인한 실제 ID를 넣는다")
        private List<Long> sourceIds;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "TopicUpdateRequest", description = "수집 주제 수정 요청. 전달한 필드만 반영되고, 키워드 배열은 전체 교체된다")
    public static class Update {

        @Schema(description = "주제명. 중복 불가. 최대 200자", example = "HBM")
        private String name;

        @Schema(description = "SEARCH 소스에 넘길 검색어. 최대 500자", example = "HBM4 반도체")
        private String queryText;

        @Schema(description = "AND 필터. 빈 배열을 보내면 필터가 사라진다", example = "[\"HBM\"]")
        private List<String> requiredKeywords;

        @Schema(description = "OR 필터. 빈 배열을 보내면 필터가 사라진다", example = "[\"SK하이닉스\"]")
        private List<String> optionalKeywords;

        @Schema(description = "NOT 필터. 빈 배열을 보내면 필터가 사라진다", example = "[\"광고\", \"채용\", \"주가\"]")
        private List<String> excludedKeywords;

        @Schema(description = "1회 수집 건수. 1 이상 100 이하", example = "30")
        private Integer batchSize;

        @Schema(description = "자동 수집 주기(분). 10 이상", example = "30")
        private Integer intervalMinutes;

        @Schema(description = "활성 여부", example = "true")
        private Boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "TopicActivationRequest", description = "수집 주제 활성 토글 요청")
    public static class Activation {

        @Schema(description = "활성 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "TopicSourceLinkRequest",
            description = "주제-소스 연결 설정 요청. 목록을 전체 교체하며, 빈 배열을 보내면 모든 연결이 해제된다")
    public static class SourceLink {

        @Schema(description = "연결할 소스 ID 목록. 소스 목록 조회로 확인한 실제 ID를 넣는다",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private List<Long> sourceIds;
    }
}
