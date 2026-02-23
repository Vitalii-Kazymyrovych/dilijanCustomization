package com.incoresoft.dilijanCustomization.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class VezhaDbDataSourceConfig {

    @Bean(name = "vezhaDataSource")
    public DataSource vezhaDataSource(VezhaDbProps props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.jdbcUrl());
        cfg.setUsername(props.getUsername());
        cfg.setPassword(props.getPassword());
        cfg.setDriverClassName("org.postgresql.Driver");
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("vezha-db-hikari");
        return new HikariDataSource(cfg);
    }

    @Bean(name = "vezhaJdbcTemplate")
    public JdbcTemplate vezhaJdbcTemplate(@Qualifier("vezhaDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
