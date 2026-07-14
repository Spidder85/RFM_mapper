package org.ikozmin.zenith.report;

import java.nio.file.Path;
import java.util.List;

/** Результат анализа XLSX-отчета Zenith: исходный файл и уникальные совпадения. */
public record ZenithReportAnalysis(
        Path reportFile,
        List<ZenithReportPerson> persons
) {
    public boolean hasPersons() {
        return !persons.isEmpty();
    }
}
