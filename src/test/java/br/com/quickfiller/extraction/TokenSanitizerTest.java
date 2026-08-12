package br.com.quickfiller.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenSanitizerTest {

    @Test
    void impossibleDateIsNotReturnedAsCertain() {
        assertThat(TokenSanitizer.safeDateRaw("38/07/2020")).isEqualTo("??/07/2020");
        assertThat(TokenSanitizer.safeDateRaw("12/13/2020")).isEqualTo("12/??/2020");
        assertThat(TokenSanitizer.safeDateRaw("31/02/2020")).isEqualTo("??/02/2020");
    }

    @Test
    void validDateAndRawTimeArePreserved() {
        assertThat(TokenSanitizer.safeDateRaw("29/02/2020")).isEqualTo("29/02/2020");
        TokenSanitizer.SanitizedTime time = TokenSanitizer.safeTime("8h05");
        assertThat(time.raw()).isEqualTo("8h05");
        assertThat(time.normalized()).isEqualTo("08:05");
    }

    @Test
    void uncertaintyRemainsVisibleInNormalizedTime() {
        TokenSanitizer.SanitizedTime time = TokenSanitizer.safeTime("0?:25");
        assertThat(time.raw()).isEqualTo("0?:25");
        assertThat(time.normalized()).isEqualTo("0?:25");
    }
}
