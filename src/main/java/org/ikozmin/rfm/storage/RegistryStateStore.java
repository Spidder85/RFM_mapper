package org.ikozmin.rfm.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ikozmin.rfm.model.CatalogType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
                    properties.getProperty(prefix + "downloadedAt")
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

    }
}
