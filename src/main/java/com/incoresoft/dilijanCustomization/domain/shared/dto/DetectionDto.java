package com.incoresoft.dilijanCustomization.domain.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DetectionDto {
  @JsonProperty("id")
  private Long id; // detection_id
  @JsonProperty("timestamp")
  private Long timestamp;
  @JsonProperty("analytics")
  private AnalyticsRef analytics; // to read stream_id
    @JsonProperty("list_item")
    private ListItemDto listItem;
    @JsonProperty("face_image")
    private String faceImage;
    @Data
  public static class AnalyticsRef {
    @JsonProperty("stream_id") private Long streamId;
  }
}

