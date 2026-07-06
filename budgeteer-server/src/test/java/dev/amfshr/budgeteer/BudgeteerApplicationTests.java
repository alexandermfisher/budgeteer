package dev.amfshr.budgeteer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test to verify the Spring application context loads correctly.
 * This is a smoke test that catches configuration issues early.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgeteerApplicationTests {

    @Test
    void contextLoads() {
        // If this test passes, the Spring context loads without errors
    }

}
