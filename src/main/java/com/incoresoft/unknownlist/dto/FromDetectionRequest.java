package com.incoresoft.unknownlist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FromDetectionRequest {
  @JsonProperty("list_id")
  private Long listId;
  @JsonProperty("detection_id")
  private Long detectionId;
  @JsonProperty("name")
  private String name;
  @JsonProperty("comment")
  private String comment;
}
