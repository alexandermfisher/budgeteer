package dev.amf.budgeteer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for the Monzo API RestClient.
 */
@Configuration
public class MonzoClientConfig {

    @Bean
    RestClient monzoRestClient(MonzoProperties monzoProperties) {
        return RestClient.builder()
                .baseUrl(monzoProperties.apiBaseUrl())
                .build();
    }
}
