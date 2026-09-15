package org.ikozmin.zenith.service;

import java.nio.file.Path;
import java.time.LocalDate;

/** Результат создания и скачивания отчета Zenith за заданный период. */
public record ZenithReportResult(
        Path reportFile,
        LocalDate beginDate,
        LocalDate endDate,
        String outDocId
) {
}
