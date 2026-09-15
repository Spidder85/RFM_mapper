package org.ikozmin.rfm.cert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ikozmin.rfm.config.AppConfig;
import org.ikozmin.rfm.crypto.CryptoProProviderRegistrar;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CryptoProCertificateLoaderTest {
    @Test
    void defaultsToJcpAndSelectsCertificateWithPrivateKey() throws Exception {
        KeyStore store = mock(KeyStore.class);
        X509Certificate cert = mock(X509Certificate.class);
        when(store.aliases()).thenReturn(Collections.enumeration(List.of("rfm")));
        when(store.getCertificate("rfm")).thenReturn(cert);
        when(cert.getSerialNumber()).thenReturn(new BigInteger("1234", 16));
        when(store.isKeyEntry("rfm")).thenReturn(true);
        try (var registration = mockConstruction(CryptoProProviderRegistrar.class);
             var stores = mockStatic(KeyStore.class)) {
            stores.when(() -> KeyStore.getInstance("HDImageStore", "JCP")).thenReturn(store);
            ClientCertificate result = new CryptoProCertificateLoader().load(new AppConfig.Certificate(), "1234");
            assertThat(result.getKeyStore()).isSameAs(store);
            assertThat(result.getAlias()).isEqualTo("rfm");
            stores.verify(() -> KeyStore.getInstance("HDImageStore", "JCP"));
        }
    }

    @Test
    void emptyStoreExplainsContainerMigration() throws Exception {
        KeyStore store = mock(KeyStore.class);
        when(store.aliases()).thenReturn(Collections.emptyEnumeration());
        try (var registration = mockConstruction(CryptoProProviderRegistrar.class);
             var stores = mockStatic(KeyStore.class)) {
            stores.when(() -> KeyStore.getInstance("HDImageStore", "JCP")).thenReturn(store);
            assertThatThrownBy(() -> new CryptoProCertificateLoader().load(new AppConfig.Certificate(), "1234"))
                    .hasRootCauseMessage("Certificate not found in JCP key store. Import the private-key container "
                            + "AND certificate into HDImageStore for the Windows account running this application. Serial: "
                            + org.ikozmin.common.logging.Masking.serial("1234"));
        }
    }

    @Test
    void rejectsOldRegistryConfigurationWithoutOpeningStore() throws Exception {
        var config = new ObjectMapper().readValue(
                "{\"CryptoPro\":{\"KeyStoreType\":\"REGISTRY\",\"KeyStoreProvider\":\"JCSP\"}}",
                AppConfig.Certificate.class);
        try (var registration = mockConstruction(CryptoProProviderRegistrar.class);
             var stores = mockStatic(KeyStore.class)) {
            assertThatThrownBy(() -> new CryptoProCertificateLoader().load(config, "1234"))
                    .hasRootCauseMessage("JCP mode requires KeyStoreProvider=JCP and a JCP key store "
                            + "(normally HDImageStore). Copy the CSP key container with its certificate to JCP first.");
            stores.verifyNoInteractions();
        }
    }
}
