package com.incoresoft.dilijanCustomization.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.meta.TelegramBotsApi;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TelegramConfig.class);

    @Test
    void registersTelegramApiBeanWhenEnabled() {
        contextRunner
                .withPropertyValues("telegram.bot.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TelegramBotsApi.class));
    }

    @Test
    void skipsTelegramApiBeanWhenDisabled() {
        contextRunner
                .withPropertyValues("telegram.bot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TelegramBotsApi.class));
    }
}
