package dev.amfshr.budgeteer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IpAddressUtil")
class IpAddressUtilTest {

    @Nested
    @DisplayName("sanitize - valid IPv4")
    class ValidIPv4 {

        @Test
        @DisplayName("should accept standard IPv4 address")
        void shouldAcceptStandardIPv4() {
            assertThat(IpAddressUtil.sanitize("192.168.1.1")).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("should accept loopback")
        void shouldAcceptLoopback() {
            assertThat(IpAddressUtil.sanitize("127.0.0.1")).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("should accept boundary values")
        void shouldAcceptBoundaryValues() {
            assertThat(IpAddressUtil.sanitize("0.0.0.0")).isEqualTo("0.0.0.0");
            assertThat(IpAddressUtil.sanitize("255.255.255.255")).isEqualTo("255.255.255.255");
        }

        @Test
        @DisplayName("should accept public IP from X-Forwarded-For")
        void shouldAcceptPublicIp() {
            assertThat(IpAddressUtil.sanitize("203.0.113.195")).isEqualTo("203.0.113.195");
        }
    }

    @Nested
    @DisplayName("sanitize - invalid IPv4")
    class InvalidIPv4 {

        @ParameterizedTest
        @DisplayName("should reject out-of-range octets")
        @ValueSource(strings = {"256.0.0.0", "192.168.1.300", "999.999.999.999"})
        void shouldRejectOutOfRangeOctets(String ip) {
            assertThat(IpAddressUtil.sanitize(ip)).isNull();
        }

        @ParameterizedTest
        @DisplayName("should reject malformed IPv4")
        @ValueSource(strings = {"192.168.1", "192.168.1.1.1", "192.168..1", ".168.1.1"})
        void shouldRejectMalformedIPv4(String ip) {
            assertThat(IpAddressUtil.sanitize(ip)).isNull();
        }
    }

    @Nested
    @DisplayName("sanitize - valid IPv6")
    class ValidIPv6 {

        @Test
        @DisplayName("should accept loopback ::1")
        void shouldAcceptIPv6Loopback() {
            assertThat(IpAddressUtil.sanitize("::1")).isNotNull();
        }

        @Test
        @DisplayName("should accept full IPv6 address")
        void shouldAcceptFullIPv6() {
            assertThat(IpAddressUtil.sanitize("2001:0db8:0000:0000:0000:0000:0000:0001")).isNotNull();
        }

        @Test
        @DisplayName("should accept compressed IPv6")
        void shouldAcceptCompressedIPv6() {
            assertThat(IpAddressUtil.sanitize("2001:db8::1")).isNotNull();
        }
    }

    @Nested
    @DisplayName("sanitize - injected / invalid input")
    class InjectedInput {

        @ParameterizedTest
        @DisplayName("should reject hostnames")
        @ValueSource(strings = {"google.com", "localhost", "evil.internal.corp"})
        void shouldRejectHostnames(String hostname) {
            assertThat(IpAddressUtil.sanitize(hostname)).isNull();
        }

        @ParameterizedTest
        @DisplayName("should reject SQL / script injection attempts")
        @ValueSource(strings = {
                "'; DROP TABLE users; --",
                "<script>alert(1)</script>",
                "1.2.3.4; rm -rf /",
                "1.2.3.4\n5.6.7.8"
        })
        void shouldRejectInjectedStrings(String injected) {
            assertThat(IpAddressUtil.sanitize(injected)).isNull();
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(IpAddressUtil.sanitize(null)).isNull();
        }

        @Test
        @DisplayName("should return null for blank input")
        void shouldReturnNullForBlank() {
            assertThat(IpAddressUtil.sanitize("   ")).isNull();
        }

        @Test
        @DisplayName("should return null for empty string")
        void shouldReturnNullForEmpty() {
            assertThat(IpAddressUtil.sanitize("")).isNull();
        }
    }
}
