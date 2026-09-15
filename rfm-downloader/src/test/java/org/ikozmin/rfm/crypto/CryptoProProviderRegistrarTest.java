package org.ikozmin.rfm.crypto;

import org.junit.jupiter.api.Test;
import java.security.Security;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CryptoProProviderRegistrarTest {
    @Test
    void rejectsExplicitJcspBeforeRegisteringAnything() {
        try (var security = mockStatic(Security.class)) {
            assertThatThrownBy(() -> new CryptoProProviderRegistrar().register(
                    new String[]{"ru.CryptoPro.JCP.JCP", "ru.CryptoPro.JCSP.JCSP"}))
                    .hasMessageContaining("Remove JCSP");
            security.verifyNoInteractions();
        }
    }

    @Test
    void missingProviderIsFatalInsteadOfSilentlyFallingBack() {
        try (var security = mockStatic(Security.class)) {
            assertThatThrownBy(() -> new CryptoProProviderRegistrar().register(
                    new String[]{"missing.Provider"}))
                    .hasMessageContaining("Failed to register").hasCauseInstanceOf(ClassNotFoundException.class);
        }
    }
}
