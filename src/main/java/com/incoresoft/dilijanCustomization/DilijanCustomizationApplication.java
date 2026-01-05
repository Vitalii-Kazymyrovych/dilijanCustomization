package com.incoresoft.dilijanCustomization;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableRetry
@RequiredArgsConstructor
public class DilijanCustomizationApplication {

    public static void main(String[] args) {
        SpringApplication.run(DilijanCustomizationApplication.class, args);
    }

}
