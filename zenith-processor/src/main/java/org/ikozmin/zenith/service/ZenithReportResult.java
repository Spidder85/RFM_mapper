package org.ikozmin.zenith.service;

import java.nio.file.Path;
import java.time.LocalDate;

public record ZenithReportResult(
        Path reportFile,
        LocalDate beginDate,
        LocalDate endDate,
        String outDocId
) {
}