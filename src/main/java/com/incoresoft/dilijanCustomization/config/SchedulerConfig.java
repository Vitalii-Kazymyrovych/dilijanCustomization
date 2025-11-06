package com.incoresoft.dilijanCustomization.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Slf4j(topic = "com.incoresoft.scheduler")
@Configuration
@EnableScheduling
public class SchedulerConfig implements SchedulingConfigurer {

    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduler-");
        // <-- This is the global error handler for @Scheduled tasks
        scheduler.setErrorHandler(t -> log.error("Scheduled task failed: {}", t.getMessage(), t));
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // Register our scheduler (with the error handler) for all @Scheduled methods
        registrar.setTaskScheduler(taskScheduler());
    }
}
