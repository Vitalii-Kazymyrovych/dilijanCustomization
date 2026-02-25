package com.incoresoft.dilijanCustomization.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.List;

// For telegrambots-spring-boot-starter 6.x this annotation is available and
// makes the starter register all TelegramLongPollingBot beans.
// If your IDE can’t resolve it, see comment below.
@Configuration
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TelegramConfig {
    @Bean
    public TelegramBotsApi telegramBotsApi(List<TelegramLongPollingBot> bots) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        for (TelegramLongPollingBot bot : bots) {
            api.registerBot(bot);
        }
        return api;
    }
}
