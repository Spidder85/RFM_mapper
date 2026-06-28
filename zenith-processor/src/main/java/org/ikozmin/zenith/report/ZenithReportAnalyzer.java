package org.ikozmin.zenith.report;

import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ZenithReportAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(ZenithReportAnalyzer.class);

    private static final String CHECKS_SHEET_NAME = "Таблица_Проверок";
    private static final String NAME_COLUMN = "ЗЛ_Наименование";
    private static final String ACCOUNT_COLUMN = "ЗЛ_НомерСчета";
    private static final String EMITENT_COLUMN = "ЭМ_Наименование";
    private static final String RISK_COLUMN = "ЗЛ_РискОснования";
}
