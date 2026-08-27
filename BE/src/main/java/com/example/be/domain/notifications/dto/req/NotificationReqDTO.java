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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private List<Long> groupIds;
        private List<Long> channelIds;
        private String idempotencyKey;
    }
}
