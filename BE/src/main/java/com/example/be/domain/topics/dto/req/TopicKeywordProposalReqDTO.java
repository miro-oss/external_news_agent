package com.example.be.domain.topics.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.util.List;

public class TopicKeywordProposalReqDTO {

    private TopicKeywordProposalReqDTO() {
    }

    @Schema(name = "TopicKeywordProposalApproveRequest", description = "키워드 변경 제안 선택 승인 요청")
    public record Approve(
            @Schema(description = "원본 changes 배열에서 적용할 0-based 인덱스. 생략 또는 null이면 전체 적용. "
                    + "빈 배열, null 원소, 중복, 음수, 범위 밖 인덱스는 허용하지 않습니다.", example = "[0, 2]")
            @JsonDeserialize(contentUsing = ChangeIndexDeserializer.class)
            List<Integer> selectedChangeIndexes
    ) {
    }

    /** Never truncate fractional indexes or coerce strings into a different keyword selection. */
    public static class ChangeIndexDeserializer extends StdDeserializer<Integer> {
        public ChangeIndexDeserializer() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "selectedChangeIndexes에는 정수만 사용할 수 있습니다.");
            }
            return parser.getIntValue();
        }
    }
}
