package com.fooyre.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.error.FieldErrorCode;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

/** Chaque code d'erreur a son message français ; l'encodage UTF‑8 est respecté. */
class MessagesCatalogTest {

    private final MessageSource messages = new MessagesConfiguration().messageSource();

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyErrorCodeHasATitleAndADetail(ErrorCode code) {
        assertThat(messages.getMessage("error." + code + ".title", null, Locale.FRENCH)).isNotBlank();
        assertThat(messages.getMessage("error." + code + ".detail", null, Locale.FRENCH)).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(FieldErrorCode.class)
    void everyFieldErrorCodeHasAMessage(FieldErrorCode code) {
        assertThat(messages.getMessage("field-error." + code, null, Locale.FRENCH)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(ints = {405, 406, 413, 415})
    void statusesWithoutStableCodeHaveMessages(int status) {
        assertThat(messages.getMessage("error.HTTP_" + status + ".title", null, Locale.FRENCH)).isNotBlank();
    }

    @Test
    void accentsAreDecodedAsUtf8() {
        assertThat(messages.getMessage("error.FORBIDDEN.title", null, Locale.FRENCH)).isEqualTo("Accès refusé");
        assertThat(messages.getMessage("error.VALIDATION_ERROR.title", null, Locale.FRENCH))
                .isEqualTo("Données invalides");
    }

    @Test
    void anUnsupportedLocaleFallsBackToFrench() {
        assertThat(messages.getMessage("error.FORBIDDEN.title", null, Locale.ENGLISH)).isEqualTo("Accès refusé");
    }

    @Test
    void anUnknownKeyFailsInsteadOfLeakingTheKey() {
        assertThatThrownBy(() -> messages.getMessage("error.UNKNOWN.title", null, Locale.FRENCH))
                .isInstanceOf(NoSuchMessageException.class);
    }
}
