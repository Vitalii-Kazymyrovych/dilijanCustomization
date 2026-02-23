package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "vezha.db")
public class VezhaDbProps {
    private boolean enabled = false;
    private String host = "localhost";
    private String port = "5432";
    private String database = "postgres";
    private String schema = "videoanalytics";
    private String username;
    private String password;

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + resolvePort() + "/" + database;
    }

    public int resolvePort() {
        if (port == null || port.isBlank()) {
            return 5432;
        }
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException ex) {
            return 5432;
        }
    }
}
