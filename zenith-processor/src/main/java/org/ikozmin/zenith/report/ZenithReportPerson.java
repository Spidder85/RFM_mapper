package org.ikozmin.zenith.report;

public record ZenithReportPerson(
        String personKey,
        String displayName,
        String normalizedName,
        String accountNumber,
        String emitentName,
        String riskReason
) {
}
