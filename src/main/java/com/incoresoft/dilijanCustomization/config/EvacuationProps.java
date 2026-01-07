package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Свойства эвакуации. Читаются из блока `evacuation` в config.yaml.
 */
@Data
@ConfigurationProperties(prefix = "evacuation")
public class EvacuationProps {
    /**
     * Сколько дней истории смотреть при вычислении статуса. 0 — всё время.
     */
    private int lookbackDays = 14;
    /**
     * Период между обновлениями статусов в минутах.
     */
    private int refreshMinutes = 5;
    /**
     * Идентификаторы списков, для которых нужно вычислять статусы.
     */
    private boolean enablesd = true;
    private boolean autostart = true;
}
