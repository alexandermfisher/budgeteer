package dev.amfshr.budgeteer.provider.monzo.autoconfigure;

import dev.amfshr.budgeteer.provider.monzo.MonzoAccountInformationProvider;
import dev.amfshr.budgeteer.provider.monzo.MonzoProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires a {@link MonzoAccountInformationProvider} from the consumer's {@code monzoRestClient} bean.
 * The consumer owns RestClient creation (baseUrl, timeouts, interceptors); this jar never
 * builds its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(MonzoProperties.class)
public class MonzoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MonzoAccountInformationProvider monzoAccountInformationProvider(
            @Qualifier("monzoRestClient") RestClient restClient,
            MonzoProperties properties,
            ObjectProvider<ObjectMapper> objectMapper
    ) {
        return new MonzoAccountInformationProvider(properties, restClient,
                objectMapper.getIfAvailable(ObjectMapper::new));
    }
}
