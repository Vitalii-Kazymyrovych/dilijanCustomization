package com.incoresoft.dilijanCustomization;

import com.incoresoft.dilijanCustomization.repository.FaceApiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;
import java.time.LocalDateTime;

@SpringBootApplication
@EnableScheduling
@EnableRetry
@RequiredArgsConstructor
public class DilijanCustomizationApplication {
    private final FaceApiRepository faceApiRepository;

    public static void main(String[] args) {
        SpringApplication.run(DilijanCustomizationApplication.class, args);
    }

}
