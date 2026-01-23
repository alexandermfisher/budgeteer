package dev.amf.budgeteer.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApiResponse} factory methods.
 */
@DisplayName("ApiResponse")
class ApiResponseTest {

    @Test
    @DisplayName("of() should create success response with data")
    void ofShouldCreateSuccessResponseWithData() {
        // Given
        String data = "test-data";

        // When
        ApiResponse<String> response = ApiResponse.of(data);

        // Then
        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("test-data");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("of() should work with complex objects")
    void ofShouldWorkWithComplexObjects() {
        // Given
        record TestData(String name, int value) {}
        TestData data = new TestData("test", 42);

        // When
        ApiResponse<TestData> response = ApiResponse.of(data);

        // Then
        assertThat(response.success()).isTrue();
        assertThat(response.data().name()).isEqualTo("test");
        assertThat(response.data().value()).isEqualTo(42);
    }

    @Test
    @DisplayName("ok() should create success response with no data")
    void okShouldCreateSuccessResponseWithNoData() {
        // When
        ApiResponse<Void> response = ApiResponse.ok();

        // Then
        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.timestamp()).isNotNull();
    }
}
