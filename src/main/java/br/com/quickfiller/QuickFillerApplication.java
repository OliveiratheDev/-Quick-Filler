package br.com.quickfiller;

import br.com.quickfiller.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class QuickFillerApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuickFillerApplication.class, args);
    }
}
