package org.ikozmin.rfm.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AuditWriter {
    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final ObjectMapper objectMapper;
    private final Path auditDir;

    public AuditWriter(Path auditDir) {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.auditDir = auditDir;
    }

    public void write(String fileName, AuditEnvelope envelope) {
        try {
            // создаем папку с полными правами
            if (!Files.exists(auditDir)) {
                Files.createDirectories(auditDir);
                // Даём полный доступ текущему пользователю
                String user = System.getProperty("user.name");
                ProcessBuilder pb = new ProcessBuilder(
                        "icacls", auditDir.toAbsolutePath().toString(),
                        "/grant", user + ":F", "/T"
                );
                pb.inheritIO();
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    log.info("Audit directory created with full permissions: {}", auditDir.toAbsolutePath());
                } else {
                    log.warn("Failed to set permissions on audit directory, exitCode={}", exitCode);
                }
            }

            Path file = auditDir.resolve(fileName);

            // Если файл существует и доступен только для чтения — даём полный доступ
            if (Files.exists(file) && !Files.isWritable(file)) {
                String user = System.getProperty("user.name");
                ProcessBuilder pb = new ProcessBuilder(
                        "icacls", file.toAbsolutePath().toString(),
                        "/grant", user + ":F"
                );
                pb.inheritIO();
                Process process = pb.start();
                process.waitFor();
                log.info("File permissions updated: {}", file.toAbsolutePath());
            }

            // Записываем файл
            objectMapper.writeValue(file.toFile(), envelope);

            log.info("Audit envelope saved: {}", file.toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save audit envelope: " + fileName, e);
        }
    }
}
