package com.incoresoft.dilijanCustomization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL settings.
 *
 * Historically this project used psql.exe via ProcessBuilder.
 * Now we also use these settings to configure Spring DataSource (JPA).
 */
@Data
@ConfigurationProperties(prefix = "postgres")
public class PostgresProps {
    /**
     * Path to psql binary (kept for backward compatibility; may be unused).
     * Example: C:/ProgramData/Incoresoft/PostgreSQL/bin/psql.exe
     */
    private String psqlPath;
    /**
     * Superuser credentials (used as defaults for JDBC if dedicated user is not provided).
     */
    private String superuser;
    private String superpass;
    /**
     * Database where evacuation table lives.
     */
    private String database;
    /**
     * JDBC connection parameters (optional; reasonable defaults are applied).
     */
    private String host = "localhost";
    /**
     * Port may be supplied as an arbitrary string in external configs; we parse it safely.
     */
    private String port = "5432";
    /** Dedicated JDBC user (optional). If empty, superuser/superpass are used. */
    private String username;
    /** Dedicated JDBC password (optional). If empty, superuser/superpass are used. */
    private String password;
    public String effectiveUsername() {
        return (username == null || username.isBlank()) ? superuser : username;
    }
    public String effectivePassword() {
        return (password == null || password.isBlank()) ? superpass : password;
    }
    public String jdbcUrl() {
        String db = (database == null || database.isBlank()) ? "postgres" : database;
        return "jdbc:postgresql://" + host + ":" + resolvePort() + "/" + db;
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
