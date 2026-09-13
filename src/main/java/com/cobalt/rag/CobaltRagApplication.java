package com.cobalt.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CobaltRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(CobaltRagApplication.class, args);
    }
}