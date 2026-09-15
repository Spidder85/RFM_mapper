package org.ikozmin.zenith.report;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Извлекает из XLSX-отчета Zenith уникальные совпадения по перечню террористов. */
public final class ZenithReportAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(ZenithReportAnalyzer.class);

    private static final String CHECKS_SHEET_NAME = "Таблица_Проверок";
    private static final String NAME_COLUMN = "ЗЛ_Наименование";
    private static final String ACCOUNT_COLUMN = "ЗЛ_НомерСчета";
    private static final String EMITENT_COLUMN = "ЭМ_Наименование";
    private static final String RISK_COLUMN = "ЗЛ_РискОснования";

    /** Анализирует отчет; отсутствие листа проверок означает отсутствие совпадений. */
    public ZenithReportAnalysis analyze(Path reportFile) {
        if (reportFile == null || !Files.isRegularFile(reportFile)) {
            throw new IllegalArgumentException("Zenith report file not found: " + reportFile);
        }

        try (InputStream input = Files.newInputStream(reportFile);
            Workbook workbook = WorkbookFactory.create(input)) {

            Sheet sheet = workbook.getSheet(CHECKS_SHEET_NAME);
            if (sheet == null) {
                log.info("Zenith report does not contain checks sheet. Treating report as empty. file={}, sheet={}",
                        reportFile.toAbsolutePath(),
                        CHECKS_SHEET_NAME);

                return new ZenithReportAnalysis(reportFile, List.of());
            }

            Map<String, Integer> columns = readHeader(sheet.getRow(0));

            int nameCol = requiredColumn(columns, NAME_COLUMN);
            int accountCol = requiredColumn(columns, ACCOUNT_COLUMN);
            int emitentCol = requiredColumn(columns, EMITENT_COLUMN);
            int riskCol = requiredColumn(columns, RISK_COLUMN);

            Map<String, ZenithReportPerson> persons = new LinkedHashMap<>();

            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String displayName = cell(row, nameCol);
                String accountNumber = cell(row, accountCol);
                String riskReason = cell(row, riskCol);

                if (displayName.isBlank() || !isTerroristMatch(riskReason)) {
                    continue;
                }

                String normalizedName = normalizeName(displayName);
                String personKey = personKey(normalizedName, accountNumber);

                persons.putIfAbsent(personKey, new ZenithReportPerson(
                        personKey,
                        displayName,
                        normalizedName,
                        accountNumber,
                        cell(row, emitentCol),
                        riskReason
                ));
            }
            log.info("Zenith report analyzed. file={}, persons={}",
                    reportFile.toAbsolutePath(),
                    persons.size());

            return new ZenithReportAnalysis(reportFile, List.copyOf(persons.values()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to analyze Zenith report: " + reportFile, e);
        }
    }

    /** Строит соответствие заголовков отчета и индексов их столбцов. */
    private Map<String, Integer> readHeader(Row row) {
        if (row == null) {
            throw new IllegalStateException("Zenith report header row is missing");
        }
        Map<String, Integer> result = new HashMap<>();

        for (Cell cell : row) {
            result.put(cell.getStringCellValue().trim(), cell.getColumnIndex());
        }

        return result;
    }

    /** Возвращает индекс обязательного столбца или сообщает о несовместимом формате отчета. */
    private int requiredColumn(Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null) {
            throw new IllegalStateException("Required column not found in Zenith report: " + name);
        }
        return index;
    }

    /** Безопасно преобразует ячейку Excel в отображаемую строку. */
    private String cell(Row row, int index) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return "";
        }

        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("ru-RU"));
        return  formatter.formatCellValue(cell);
    }

    /** Отбирает совпадения, относящиеся именно к перечню террористов. */
    private boolean isTerroristMatch(String riskReason) {
        return riskReason != null
                && riskReason.toLowerCase(Locale.forLanguageTag("ru-Ru")).contains("списке террористов");
    }

    /** Нормализует ФИО для устойчивого поиска повторяющихся записей. */
    private String normalizeName(String value) {
        return value == null
                ? ""
                : value.trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.forLanguageTag("ru-RU"));
    }

    /** Формирует ключ уникальности совпадения из ФИО и номера счета. */
    private String personKey(String normalizedName, String accountNumber) {
        String normalizedAccount = accountNumber == null
                ? ""
                : accountNumber.trim().replaceAll("\\s+", "");

        return normalizedName + "|" + normalizedAccount;
    }
}
