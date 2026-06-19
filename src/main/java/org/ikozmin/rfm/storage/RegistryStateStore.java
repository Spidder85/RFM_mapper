package org.ikozmin.rfm.storage;

import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.ikozmin.rfm.model.CatalogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegistryStateStore {
    private static final Logger log = LoggerFactory.getLogger(RegistryStateStore.class);

    private final Path path;

    public RegistryStateStore(Path path) {
        this.path = path;
    }

    public RegistryState load(CatalogType catalogType) {
        try {
            Properties properties = loadProperties();
            String prefix = catalogType.getCode() + ".";

            String idXml = properties.getProperty(prefix + "idXml");

            if (idXml == null || idXml.trim().isEmpty()) {
                log.info("No local state for catalog {}", catalogType.getCode());
                return null;
            }

            RegistryState state = new RegistryState(
                    idXml,
                    properties.getProperty(prefix + "date"),
                    properties.getProperty(prefix + "file"),
                    properties.getProperty(prefix + "downloadedAt"),
                    properties.getProperty(prefix + "sha256")
            );

            log.info("Local state loaded. catalog={}, idXml={}, file={}",
                    catalogType.getCode(),
                    state.getIdXml(),
                    state.getFile());

            return state;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load registry state: " + path.toAbsolutePath(), e);
        }
    }

    public void save(CatalogType catalogType, RegistryState state) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());

            Properties properties = loadProperties();
            String prefix = catalogType.getCode() + ".";

            properties.setProperty(prefix + "idXml", nullToEmpty(state.getIdXml()));
            properties.setProperty(prefix + "date", nullToEmpty(state.getDate()));
            properties.setProperty(prefix + "file", nullToEmpty(state.getFile()));
            properties.setProperty(prefix + "downloadedAt", nullToEmpty(state.getDownloadedAt()));
            properties.setProperty(prefix + "sha256", nullToEmpty(state.getSha256()));

            Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");

            try (var output = Files.newOutputStream(tempPath)) {
                properties.store(output, "RFM registry state");
            }

            Files.move(
                    tempPath,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );

            log.info("Local state saved. catalog={}, idXml={}, file={}",
                catalogType.getCode(),
                state.getIdXml(),
                state.getFile());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save registry state: " + path.toAbsolutePath(), e);
        }
    }

    private Properties loadProperties() throws IOException {
        Properties properties = new Properties();

        if (!Files.exists(path)) {
            return properties;
        }

        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }

        return properties;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
