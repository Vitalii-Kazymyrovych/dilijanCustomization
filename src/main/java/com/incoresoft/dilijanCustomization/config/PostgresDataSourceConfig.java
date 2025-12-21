package com.incoresoft.dilijanCustomization.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Creates a DataSource from {@link PostgresProps}.
 *
 * This is needed because the project uses external config.yaml and does not define
 * spring.datasource.* properties, so Spring Boot cannot auto-configure datasource.
 */
@Configuration
@RequiredArgsConstructor
public class PostgresDataSourceConfig {

    private final PostgresProps props;

    @Bean
    public DataSource dataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.jdbcUrl());
        cfg.setUsername(props.effectiveUsername());
        cfg.setPassword(props.effectivePassword());
        cfg.setDriverClassName("org.postgresql.Driver");

        // sane defaults
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("dilijanCustomization-hikari");

        return new HikariDataSource(cfg);
    }
}
