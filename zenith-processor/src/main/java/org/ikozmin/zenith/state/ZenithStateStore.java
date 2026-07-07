package org.ikozmin.zenith.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;

public final class ZenithStateStore {
    private final Path file;

    public ZenithStateStore(Path file) {
        this.file = file;
    }

    public Optional<LocalDate> loadLastSuccessfulCheckDate(String catalog) {
        Properties properties = loadProperties();
        String value = properties.getProperty(key(catalog, "lastSuccessfulCheckDate"));

        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(LocalDate.parse(value));
    }

    private Properties loadProperties() {
        Properties properties = new Properties();

        if (!Files.isRegularFile(file)) {
            return properties;
        }

        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Zenith state: " + file.toAbsolutePath(), e);
        }
    }

    public void saveSuccessfulCheck(String catalog, LocalDate checkDate, String idXml, String eventId) {
        try {
            Files.createDirectories(file.getParent());

            Properties properties = loadProperties();
            properties.setProperty(key(catalog, "lastSuccessfulCheckDate"), checkDate.toString());
            properties.setProperty(key(catalog, "lastSuccessfulIdXml"), idXml == null ? "" : idXml);
            properties.setProperty(key(catalog, "lastSuccessfulEventId"), eventId == null ? "" : eventId);

            try (var writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "Zenith processing state");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Zenith state: " + file.toAbsolutePath(), e);
        }
    }

    private String key(String catalog, String name) {
        String normalizedCatalog = catalog == null || catalog.isBlank()
                ? "unknown"
                : catalog.trim().toLowerCase();

        return normalizedCatalog + "." + name;
    }
}