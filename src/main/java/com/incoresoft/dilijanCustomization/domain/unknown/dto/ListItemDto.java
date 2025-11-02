package com.incoresoft.dilijanCustomization.domain.unknown.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ListItemDto {
  @JsonProperty("id")
  private Long id;
  @JsonProperty("name")
  private String name;
  @JsonProperty("list_id")
  private Long listId;
  @JsonProperty("comment")
  private String comment;
}