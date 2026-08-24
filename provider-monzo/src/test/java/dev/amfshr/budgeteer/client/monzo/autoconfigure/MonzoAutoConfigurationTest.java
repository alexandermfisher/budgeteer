package dev.amfshr.budgeteer.client.monzo.autoconfigure;

import dev.amfshr.budgeteer.client.monzo.MonzoAccountInformationProvider;
import dev.amfshr.budgeteer.client.monzo.MonzoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("MonzoAutoConfiguration")
class MonzoAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MonzoAutoConfiguration.class))
            .withPropertyValues(
                    "monzo.client-id=cid", "monzo.client-secret=secret",
                    "monzo.redirect-uri=http://localhost/cb", "monzo.auth-url=http://auth",
                    "monzo.token-url=http://token", "monzo.api-base-url=http://api");

    @Test
    @DisplayName("registers MonzoAccountInformationProvider when monzoRestClient bean present")
    void registersMonzoAccountInformationProviderWhenRestClientPresent() {
        runner.withBean("monzoRestClient", RestClient.class, RestClient::create)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MonzoAccountInformationProvider.class);
                    assertThat(ctx).hasSingleBean(MonzoProperties.class);
                });
    }

    @Test
    @DisplayName("backs off when consumer defines own MonzoAccountInformationProvider")
    void backsOffWhenConsumerDefinesOwnMonzoAccountInformationProvider() {
        MonzoAccountInformationProvider custom = mock(MonzoAccountInformationProvider.class);
        runner.withBean("monzoRestClient", RestClient.class, RestClient::create)
                .withBean("customClient", MonzoAccountInformationProvider.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(MonzoAccountInformationProvider.class)).isSameAs(custom));
    }

    @Test
    @DisplayName("fails without monzoRestClient bean")
    void failsWithoutMonzoRestClientBean() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }
}
