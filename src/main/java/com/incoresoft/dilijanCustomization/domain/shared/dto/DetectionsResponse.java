package com.incoresoft.dilijanCustomization.domain.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class DetectionsResponse {
  @JsonProperty("data")
  private List<DetectionDto> data;
  @JsonProperty("total")
  private Integer total;
  @JsonProperty("pages")
  private Integer pages;
  @JsonProperty("status")
  private String status;
}