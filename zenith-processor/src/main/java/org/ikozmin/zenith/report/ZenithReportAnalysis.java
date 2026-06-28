package org.ikozmin.zenith.report;

import java.nio.file.Path;
import java.util.List;

public record ZenithReportAnalysis(
        Path reportFile,
        List<ZenithReportPerson> persons
) {
    public boolean hasPersons() {
        return !persons.isEmpty();
    }
}