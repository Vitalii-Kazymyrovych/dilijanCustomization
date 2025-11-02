package com.incoresoft.dilijanCustomization.domain.unknown.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ListItemsResponse {
  @JsonProperty("data")
  private List<ListItemDto> data;
  @JsonProperty("total")
  private Integer total;
  @JsonProperty("pages")
  private Integer pages;
  @JsonProperty("status")
  private String status;
}