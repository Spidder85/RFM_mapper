package org.ikozmin.rfm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ikozmin.rfm.exception.RfmConfigException;
import org.ikozmin.rfm.logging.Masking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AppConfig load(Path path) {
        if (!Files.exists(path)) {
            throw new RfmConfigException("Config file not found: " + path.toAbsolutePath());
        }

        try {
            AppConfig config = objectMapper.readValue(path.toFile(), AppConfig.class);
            validate(config);

            log.info("Config loaded: {}", path.toAbsolutePath());
            log.info("Config userName: {}", Masking.userName(userName(config)));
            log.info("Config certificate serial: {}", Masking.serial(certificateSerial(config)));
            log.info("Config default catalog: {}", defaultCatalog(config));
            log.info("Config contour: {}", config.isUseTestContour() ? "test" : "prod");

            return config;
        } catch (IOException e) {
            throw new RfmConfigException("Failed to read config: " + path.toAbsolutePath(), e);
        }
    }

    public String userName(AppConfig config) {
        return envOrConfig("RFM_USERNAME", config.getCredentials().getUserName());
    }

    public String password(AppConfig config) {
        return envOrConfig("RFM_PASSWORD", config.getCredentials().getPassword());
    }

    public String certificateSerial(AppConfig config) {
        return envOrConfig("RFM_CERT_SERIAL", config.getCertificate().getSerialNumber());
    }

    public String defaultCatalog(AppConfig config) {
        String value = config.getDefaultCatalog();
        return isBlank(value) ? "te21" : value;
    }

    private void validate(AppConfig config) {
        if (config == null) {
            throw new RfmConfigException("Config is empty");
        }

        if (config.getCredentials() == null) {
            throw new RfmConfigException("Credentials section is missing");
        }

        if (isBlank(config.getCredentials().getUserName()) && isBlank(System.getenv("RFM_USERNAME"))) {
            throw new RfmConfigException("Credentials.UserName is empty");
        }

        if (isBlank(config.getCredentials().getPassword()) && isBlank(System.getenv("RFM_PASSWORD"))) {
            throw new RfmConfigException("Credentials.Password is empty");
        }

        if (config.getCertificate() == null) {
            throw new RfmConfigException("Certificate section is missing");
        }

        if (isBlank(config.getCertificate().getSerialNumber()) && isBlank(System.getenv("RFM_CERT_SERIAL"))) {
            throw new RfmConfigException("Certificate.SerialNumber is empty");
        }
    }

    private static String envOrConfig(String envName, String configValue) {
        String envValue = System.getenv(envName);
        return isBlank(envValue) ? configValue : envValue;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
