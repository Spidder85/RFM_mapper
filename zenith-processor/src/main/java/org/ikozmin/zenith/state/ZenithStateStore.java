package org.ikozmin.zenith.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;

/** Хранит служебное состояние Zenith между запусками. */
public final class ZenithStateStore {
    private final Path file;

    /** Открывает указанное файловое хранилище состояния. */
    public ZenithStateStore(Path file) {
        this.file = file;
    }

    /** Возвращает дату последней успешной проверки данного перечня. */
    public Optional<LocalDate> loadLastSuccessfulCheckDate(String catalog) {
        Properties properties = loadProperties();
        String value = properties.getProperty(key(catalog, "lastSuccessfulCheckDate"));

        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(LocalDate.parse(value));
    }

    /** Загружает properties-файл или создает пустое состояние при первом запуске. */
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

    /** Сохраняет контрольную дату и идентификаторы успешно обработанного реестра. */
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

    /** Строит namespaced-ключ свойства, исключающий пересечение разных перечней. */
    private String key(String catalog, String name) {
        String normalizedCatalog = catalog == null || catalog.isBlank()
                ? "unknown"
                : catalog.trim().toLowerCase();

        return normalizedCatalog + "." + name;
    }
}
