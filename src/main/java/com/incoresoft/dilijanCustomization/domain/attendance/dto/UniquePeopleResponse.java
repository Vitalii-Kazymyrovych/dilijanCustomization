package com.incoresoft.dilijanCustomization.domain.attendance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class UniquePeopleResponse {
    @JsonProperty("data")   private JsonNode data;   // day -> hour -> listId|unlisted -> { 0,1,2,total }
    @JsonProperty("status") private String status;
}