package com.acme.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EntityGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EntityGatewayApplication.class, args);
    }
}
