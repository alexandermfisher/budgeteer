package dev.amfshr.budgeteer.provider.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SyncPosition")
class SyncPositionTest {

    @Test
    @DisplayName("FromTime rejects null")
    void fromTimeRejectsNull() {
        assertThatThrownBy(() -> new SyncPosition.FromTime(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("AfterTransaction rejects null")
    void afterTransactionRejectsNull() {
        assertThatThrownBy(() -> new SyncPosition.AfterTransaction(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("NextPage rejects null")
    void nextPageRejectsNull() {
        assertThatThrownBy(() -> new SyncPosition.NextPage(null))
                .isInstanceOf(NullPointerException.class);
    }
}
