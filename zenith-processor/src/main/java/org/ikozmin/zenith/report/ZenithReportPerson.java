package org.ikozmin.zenith.report;

/** Строка совпадения, выделенная из отчета массовой проверки Zenith. */
public record ZenithReportPerson(
        String personKey,
        String displayName,
        String normalizedName,
        String accountNumber,
        String emitentName,
        String riskReason
) {
}
