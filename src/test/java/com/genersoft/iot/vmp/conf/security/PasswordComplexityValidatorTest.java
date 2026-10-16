package com.genersoft.iot.vmp.conf.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class PasswordComplexityValidatorTest {
    private final PasswordComplexityValidator validator = new PasswordComplexityValidator();

    @Test void sharedFrontendBackendCases() throws Exception {
        try (var input = getClass().getResourceAsStream("/security/password-policy-cases.json")) {
            assertNotNull(input);
            for (JsonNode test : new ObjectMapper().readTree(input)) {
                assertEquals(test.get("valid").asBoolean(), validator.validatePassword(
                        test.get("password").isNull() ? null : test.get("password").asText(),
                        test.get("username").isNull() ? null : test.get("username").asText()), test.toString());
            }
        }
    }

    @Test void everyAsciiPunctuationIsAcceptedIncludingQuoteAndBackslash() {
        for (char value = '!'; value <= '~'; value++) {
            if (!Character.isLetterOrDigit(value)) {
                assertTrue(validator.validatePassword("Abcdefg12" + value, "operator"), "punctuation=" + value);
            }
        }
    }

    @Test void usernameCheckDoesNotDependOnServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertFalse(validator.validatePassword("ADMINabc12!", "admin"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
