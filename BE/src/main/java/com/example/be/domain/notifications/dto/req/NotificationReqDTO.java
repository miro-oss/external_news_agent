package com.example.be.domain.notifications.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

public class NotificationReqDTO {

    private NotificationReqDTO() {
    }

    @Getter @Setter @NoArgsConstructor
    public static class RunDeliverySettings {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean enabled;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean run;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean daily;
        private List<Long> groupIds;
        private List<Long> recipientIds;
        private List<Long> channelIds;
    }

    @Getter @Setter @NoArgsConstructor
    public static class ChannelUpdate {
        private String name;
        private Map<String, Object> config;
        private Integer maxLength;
        private Boolean active;
    }

    @Getter @Setter @NoArgsConstructor
    public static class RecipientCreate {
        private String name;
        private String phone;
        private String email;
        private String memo;
        private Boolean active;
        private List<DestinationInput> destinations;
    }

    @Getter @Setter @NoArgsConstructor
    public static class RecipientUpdate {
        private String name;
        private String phone;
        private String email;
        private String memo;
        private Boolean active;
    }

    @Getter @Setter @NoArgsConstructor
    public static class DestinationInput {
        private Long channelId;
        private String address;
        private Boolean use;
    }

    @Getter @Setter @NoArgsConstructor
    public static class DestinationsUpdate {
        private List<DestinationInput> destinations;
    }

    @Getter @Setter @NoArgsConstructor
    public static class GroupCreate {
        private String name;
        private String perspective;
        private Boolean active;
        private List<Long> recipientIds;
    }

    @Getter @Setter @NoArgsConstructor
    public static class GroupUpdate {
        private String name;
        private String perspective;
        private Boolean active;
    }

    @Getter @Setter @NoArgsConstructor
    public static class MembersUpdate {
        private List<Long> recipientIds;
    }

    @Getter @Setter @NoArgsConstructor
    public static class Preview {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        private Long channelId;
    }

    @Getter @Setter @NoArgsConstructor
    public static class Send {
        @Schema(description = "그룹 또는 개별 수신자 중 하나 이상을 지정합니다.")
        private List<Long> groupIds;
        private List<Long> recipientIds;
        private List<Long> channelIds;
        @Schema(description = "중복 발송 방지 키. 같은 키는 실패한 배치를 포함해 기존 결과만 반환합니다. "
                + "사용자가 전체 실패 후 새 발송을 명시적으로 요청할 때만 새 키를 사용합니다.")
        private String idempotencyKey;
    }
}
