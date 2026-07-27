package dev.snip;

import dev.snip.config.SnipProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SnipProperties.class)
public class SnipApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnipApplication.class, args);
    }
}
