package dev.amf.budgeteer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("dev.amf.budgeteer.config")
public class BudgeteerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BudgeteerApplication.class, args);
    }

}
