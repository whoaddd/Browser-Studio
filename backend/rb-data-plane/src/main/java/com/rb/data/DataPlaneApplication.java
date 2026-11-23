package com.rb.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = {"com.rb.data", "com.rb.common"})
@EnableScheduling
public class DataPlaneApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataPlaneApplication.class, args);
    }
}