package com.incoresoft.dilijanCustomization.domain.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * Конфигурация списков с камерами входа и выхода.
 */
@Data
public class ListConfigDto {
    @JsonProperty("entrance_streams")
    private List<Long> entranceStreams;

    @JsonProperty("exit_streams")
    private List<Long> exitStreams;
}
