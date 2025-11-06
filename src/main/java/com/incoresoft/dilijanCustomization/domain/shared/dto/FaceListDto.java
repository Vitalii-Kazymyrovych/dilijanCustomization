package com.incoresoft.dilijanCustomization.domain.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class FaceListDto {
    @JsonProperty("id")    private Long id;
    @JsonProperty("name")  private String name;
    @JsonProperty("comment") private String comment;
    @JsonProperty("analytics_ids") private List<Long> analyticsIds;
}