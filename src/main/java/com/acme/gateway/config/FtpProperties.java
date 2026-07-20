package com.acme.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.ftp")
public record FtpProperties(
        String host,
        int port,
        String username,
        String password,
        String basePath,
        Duration connectTimeout,
        Duration dataTimeout,
        Duration controlKeepAlive) {
}
