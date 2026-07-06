package dev.amfshr.budgeteer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("dev.amfshr.budgeteer.config")
@EnableScheduling
public class BudgeteerApplication {

    static void main(String[] args) {
        SpringApplication.run(BudgeteerApplication.class, args);
    }

}
