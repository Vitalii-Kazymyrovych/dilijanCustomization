package com.incoresoft.dilijanCustomization.domain.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
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
  @JsonProperty("created_at")
  private Object createdAt;

  public Long resolveCreatedAtMillis() {
    if (createdAt == null) {
      return null;
    }
    if (createdAt instanceof Number number) {
      long raw = number.longValue();
      return raw < 1_000_000_000_000L ? raw * 1000 : raw;
    }
    if (createdAt instanceof String raw) {
      String value = raw.trim();
      if (value.isEmpty()) {
        return null;
      }
      try {
        long parsed = Long.parseLong(value);
        return parsed < 1_000_000_000_000L ? parsed * 1000 : parsed;
      } catch (NumberFormatException ignored) {
        try {
          return Instant.parse(value).toEpochMilli();
        } catch (Exception ignoredToo) {
          return null;
        }
      }
    }
    return null;
  }
}
