package com.incoresoft.dilijanCustomization.domain.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

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
  @JsonProperty("images")
    private List<ListImage> images;
}