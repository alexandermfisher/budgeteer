package dev.amf.budgeteer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("dev.amf.budgeteer.config")
@EnableScheduling
public class BudgeteerApplication {

    static void main(String[] args) {
        SpringApplication.run(BudgeteerApplication.class, args);
    }

}
