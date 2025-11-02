package com.incoresoft.dilijanCustomization.domain.attendance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class FaceListsResponse {
    @JsonProperty("data")   private List<FaceListDto> data;
    @JsonProperty("status") private String status;
    @JsonProperty("total")  private Integer total;
    @JsonProperty("pages")  private Integer pages;
}