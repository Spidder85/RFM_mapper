package org.ikozmin.zenith.person;

import org.ikozmin.zenith.report.ZenithReportPerson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FoundPersonsStore {
    private static final String HEADER = "personKey\tdisplayName\tnormalizedName\taccountNumber\tfirstFoundDate\tlastFoundDate\tfesPrepared\tfesSent";

    private final Path file;

    public FoundPersonsStore(Path file) {
        this.file = file;
    }

    public Map<String, StoredPerson> load() {
        try {
            if (!Files.isRegularFile(file)) {
                return new LinkedHashMap<>();
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, StoredPerson> result = new LinkedHashMap<>();

            for (String line : lines) {
                if (line.isBlank() || line.equals(HEADER)) {
                    continue;
                }

                String[] parts = line.split("\t", -1);
                if (parts.length < 8) {
                    continue;
                }

                StoredPerson person = new StoredPerson(
                        parts[0],
                        parts[1],
                        parts[2],
                        parts[3],
                        LocalDate.parse(parts[4]),
                        LocalDate.parse(parts[5]),
                        Boolean.parseBoolean(parts[6]),
                        Boolean.parseBoolean(parts[7])
                );
                result.put(person.personKey(), person);
            }

            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load found persons DB: " + file, e);
        }
    }

    public List<ZenithReportPerson> findNewPersons(List<ZenithReportPerson> reportPersons, LocalDate foundDate) {
        Map<String, StoredPerson> stored = load();
        List<ZenithReportPerson> newPersons = new ArrayList<>();

        for (ZenithReportPerson person : reportPersons) {
            StoredPerson existing = stored.get(person.personKey());

            if (existing == null) {
                newPersons.add(person);
                stored.put(person.personKey(), new StoredPerson(
                        person.personKey(),
                        person.displayName(),
                        person.normalizedName(),
                        person.accountNumber(),
                        foundDate,
                        foundDate,
                        false,
                        false
                ));
            } else {
                stored.put(person.personKey(), new StoredPerson(
                        existing.personKey(),
                        existing.displayName(),
                        existing.normalizedName(),
                        existing.accountNumber(),
                        existing.firstFoundDate(),
                        foundDate,
                        existing.fesPrepared(),
                        existing.fesSent()
                ));
            }
        }

        save(stored);
        return newPersons;
    }

    public void markFesPrepared(Collection<ZenithReportPerson> persons) {
        Map<String, StoredPerson> stored = load();

        for (ZenithReportPerson person : persons) {
            StoredPerson existing = stored.get(person.personKey());
            if (existing == null) {
                continue;
            }

            stored.put(person.personKey(), new StoredPerson(
                    existing.personKey(),
                    existing.displayName(),
                    existing.normalizedName(),
                    existing.accountNumber(),
                    existing.firstFoundDate(),
                    existing.lastFoundDate(),
                    true,
                    existing.fesSent()
            ));
        }
        save(stored);
    }

    private void save(Map<String, StoredPerson> persons) {
        try {
            Files.createDirectories(file.getParent());

            List<String> lines = new ArrayList<>();
            lines.add(HEADER);

            for (StoredPerson person : persons.values()) {
                lines.add(String.join("\t",
                        person.personKey(),
                        person.displayName(),
                        person.normalizedName(),
                        person.accountNumber(),
                        person.firstFoundDate().toString(),
                        person.lastFoundDate().toString(),
                        Boolean.toString(person.fesPrepared()),
                        Boolean.toString(person.fesSent())
                ));
            }

            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save found persons DB: " + file, e);
        }
    }
}
