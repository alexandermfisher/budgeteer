package dev.amfshr.budgeteer.config;

import dev.amfshr.budgeteer.provider.monzo.MonzoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The app owns the Monzo RestClient — baseUrl, and later timeouts/interceptors/pooling.
 * MonzoAutoConfiguration (bank-client-monzo jar) picks this bean up by name.
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
