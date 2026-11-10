package com.github.fortress4j.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class BasicUsageExampleTest {

    @Test
    void shouldRunBasicUsageExampleWithoutErrors() {
        assertDoesNotThrow(() -> BasicUsageExample.main(new String[0]));
    }
}

