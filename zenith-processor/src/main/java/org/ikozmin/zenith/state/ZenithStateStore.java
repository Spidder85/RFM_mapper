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

    public Optional<LocalDate> loadLastSuccessfulCheckDate() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Zenith state: " + file.toAbsolutePath(), e);
        }

        String value = properties.getProperty("lastSuccessfulCheckDate");

        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(LocalDate.parse(value));
    }

    public void saveSuccessfulCheck(LocalDate checkDate, String idXml, String eventId) {
        try {
            Files.createDirectories(file.getParent());

            Properties properties = new Properties();
            properties.setProperty("lastSuccessfulCheckDate", checkDate.toString());
            properties.setProperty("lastSuccessfulIdXml", idXml == null ? "" : idXml);
            properties.setProperty("lastSuccessfulEventId", eventId == null ? "" : eventId);

            try (var writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "Zenith processing state");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save Zenith state: " + file.toAbsolutePath(), e);
        }
    }
}