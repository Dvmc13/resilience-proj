package com.example.resilience_library;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ResilienceLibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResilienceLibraryApplication.class, args);
    }
}
