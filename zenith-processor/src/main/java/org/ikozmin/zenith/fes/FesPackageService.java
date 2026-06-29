package org.ikozmin.zenith.fes;

import org.ikozmin.zenith.report.ZenithReportPerson;
import org.ikozmin.zenith.service.ZenithReportResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FesPackageService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final Path outputDir;

    public FesPackageService(Path outputDir) {
        this.outputDir = outputDir;
    }

    public List<FesPackage> preparePackages(List<ZenithReportPerson> persons, ZenithReportResult reportResult) {
        try {
            List<FesPackage> result = new ArrayList<>();

            for (ZenithReportPerson person : persons) {
                String safeName = safeFileName(person.personKey());
                Path dir = outputDir
                        .resolve(reportResult.endDate().format(DATE))
                        .resolve(safeName);

                Files.createDirectories(dir);

                Path xml = dir.resolve("FM03_DRAFT_" + safeName + ".xml");
                Path sign = dir.resolve("FM03_DRAFT_" + safeName + ".xml.sig");

                Files.writeString(xml, buildDraftXml(person, reportResult), StandardCharsets.UTF_8);

                if (!Files.exists(sign)) {
                    Files.writeString(sign,
                            "Черновик. Настоящая detached-подпись CryptoPro на этом этапе не формируется.",
                            StandardCharsets.UTF_8);
                }

                result.add(new FesPackage(person.displayName(), dir, xml, sign));
            }

            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare FES packages", e);
        }
    }

    private String buildDraftXml(ZenithReportPerson person, ZenithReportResult reportResult) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!--
                  Черновик ФЭС FM03.
                  Отправка в Росфинмониторинг на этом этапе не выполняется.
                  Перед реальной отправкой XML нужно привести к утвержденному формату ФЭС
                  и подписать detached-подписью CryptoPro.
                -->
                <FM03_DRAFT>
                    <PersonKey>%s</PersonKey>
                    <PersonName>%s</PersonName>
                    <AccountNumber>%s</AccountNumber>
                    <EmitentName>%s</EmitentName>
                    <RiskReason>%s</RiskReason>
                    <CheckBeginDate>%s</CheckBeginDate>
                    <CheckEndDate>%s</CheckEndDate>
                    <ZenithOutDocId>%s</ZenithOutDocId>
                    <SourceReport>%s</SourceReport>
                </FM03_DRAFT>
                """.formatted(
                escapeXml(person.personKey()),
                escapeXml(person.displayName()),
                escapeXml(person.accountNumber()),
                escapeXml(person.emitentName()),
                escapeXml(person.riskReason()),
                reportResult.beginDate(),
                reportResult.endDate(),
                escapeXml(reportResult.outDocId()),
                escapeXml(reportResult.reportFile().toString())
        );
    }

    private String safeFileName(String value) {
        return value.toUpperCase(Locale.forLanguageTag("ru-RU"))
                .replaceAll("[^А-ЯA-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
