package com.incoresoft.dilijanCustomization.domain.unknown.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FaceEventDto {
  @JsonProperty("timestamp")
  private Long timestamp; // exact epoch
  @JsonProperty("in_list")
  private boolean inList;
    @JsonProperty("face")
    private FacePayload face;
    @Data
    public static class FacePayload {
        @JsonProperty("list_item")
        private ListItemRef listItem;                  // ← present when face is in a list

        @JsonProperty("analytics")
        private AnalyticsRef analytics;                // optional, if you need stream_id later
    }

    @Data
    public static class ListItemRef {
        @JsonProperty("id")
        private Long id;                               // list_item_id

        @JsonProperty("name")
        private String name;

        @JsonProperty("list")
        private PersonListRef list;                    // nested list
    }

    @Data
    public static class PersonListRef {
        @JsonProperty("id")
        private Long id;                               // list_id

        @JsonProperty("name")
        private String name;
    }

    @Data
    public static class AnalyticsRef {
        @JsonProperty("stream_id")
        private Long streamId;                         // if VEZHA sends it
    }

    @JsonProperty("thumbnail_image")
    private String faceImage;
}
