package com.incoresoft.dilijanCustomization.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresPropsTest {

    @Test
    void usesDedicatedCredentialsWhenProvided() {
        PostgresProps props = new PostgresProps();
        props.setSuperuser("admin");
        props.setSuperpass("secret");
        props.setUsername("app");
        props.setPassword("pass");

        assertThat(props.effectiveUsername()).isEqualTo("app");
        assertThat(props.effectivePassword()).isEqualTo("pass");
    }

    @Test
    void fallsBackToSuperuserCredentialsWhenDedicatedMissing() {
        PostgresProps props = new PostgresProps();
        props.setSuperuser("admin");
        props.setSuperpass("secret");
        props.setUsername("   ");
        props.setPassword(null);

        assertThat(props.effectiveUsername()).isEqualTo("admin");
        assertThat(props.effectivePassword()).isEqualTo("secret");
    }

    @Test
    void jdbcUrlUsesFallbackDatabaseAndPort() {
        PostgresProps props = new PostgresProps();
        props.setHost("db-host");
        props.setDatabase(" ");
        props.setPort("invalid");

        assertThat(props.resolvePort()).isEqualTo(5432);
        assertThat(props.jdbcUrl()).isEqualTo("jdbc:postgresql://db-host:5432/postgres");
    }

    @Test
    void resolvePortParsesTrimmedValue() {
        PostgresProps props = new PostgresProps();
        props.setPort("  6543  ");

        assertThat(props.resolvePort()).isEqualTo(6543);
    }
}
