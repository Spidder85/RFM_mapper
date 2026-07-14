package org.ikozmin.zenith.config;

import org.ikozmin.common.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/** Загружает, нормализует и проверяет конфигурацию zenith-processor. */
public final class ZenithConfigLoader {
    public ZenithConfig load(Path path) {
        try {
            if (!Files.exists(path)) {
                throw new IllegalStateException("Zenith config not found: " + path.toAbsolutePath());
            }

            ZenithConfig config = JsonMapper.get().readValue(path.toFile(), ZenithConfig.class);
            validate(config);
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Zenith config: " + path.toAbsolutePath(), e);
        }
    }

    private void validate(ZenithConfig config) {
        if (config == null || config.getZenith() == null) {
            throw new IllegalStateException("Zenith config section is missing");
        }

        if (isBlank(config.getZenith().getBaseUrl())) {
            throw new IllegalStateException("Zenith.BaseUrl is empty");
        }

        if (isBlank(config.getZenith().getUserName())) {
            throw new IllegalStateException("Zenith.UserName is empty");
        }

        if (isBlank(config.getZenith().getPassword())) {
            throw new IllegalStateException("Zenith.Password is empty");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
