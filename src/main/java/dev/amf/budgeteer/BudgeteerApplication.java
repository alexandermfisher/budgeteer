package dev.amf.budgeteer;

import dev.amf.budgeteer.config.MonzoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MonzoProperties.class)
public class BudgeteerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BudgeteerApplication.class, args);
    }

}
