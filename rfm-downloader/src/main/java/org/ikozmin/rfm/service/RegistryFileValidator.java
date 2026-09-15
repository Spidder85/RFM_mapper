package org.ikozmin.rfm.service;

import org.ikozmin.rfm.model.CatalogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Проверяет, что скачанный файл соответствует ожидаемому формату реестра. */
public final class RegistryFileValidator {
    private static final Logger log = LoggerFactory.getLogger(RegistryFileValidator.class);

    /** Проверяет структуру ZIP или XML до сохранения новой версии реестра как актуальной. */
    public void validate(CatalogType catalogType, Path file) {
        if (catalogType == CatalogType.UN || catalogType == CatalogType.UN_RUS) {
            validateXml(file);
        } else {
            validateZip(file);
        }
    }

    /** Проверяет, что ZIP читается и содержит допустимый набор файлов. */
    private void validateZip(Path file) {
        try (ZipInputStream zipInputStream = new ZipInputStream(
                Files.newInputStream(file),
                Charset.forName("CP866"))) {
            ZipEntry entry = zipInputStream.getNextEntry();

            if (entry == null) {
                throw new IllegalStateException("ZIP archive is empty: " + file.toAbsolutePath());
            }

            boolean hasXml = false;
            boolean hasAnyFile = false;

            while (entry != null) {
                if (!entry.isDirectory()) {
                    hasAnyFile = true;

                    if (entry.getName().toLowerCase().endsWith(".xml")) {
                        hasXml = true;
                    }
                }

                entry = zipInputStream.getNextEntry();
            }

            if (!hasAnyFile) {
                throw new IllegalStateException("ZIP archive contains no files: " + file.toAbsolutePath());
            }

            log.info("ZIP registry file validated. path={}, hasXml={}", file.toAbsolutePath(), hasXml);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid ZIP registry file: " + file.toAbsolutePath(), e);
        }
    }

    /** Проверяет, что XML-файл непустой и может быть разобран парсером. */
    private void validateXml(Path file) {
        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

            try {
                while (reader.hasNext()) {
                    reader.next();
                    if (reader.isStartElement()) {
                        log.info("XML registry file validated. path={}, rootElement={}",
                                file.toAbsolutePath(),
                                reader.getLocalName());
                        return;
                    }
                }

                throw new IllegalStateException("XML file has no root element: " + file.toAbsolutePath());
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid XML registry file: " + file.toAbsolutePath(), e);
        }
    }
}
