package org.ikozmin.rfm.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            Files.createDirectories(auditDir);

            Path file = auditDir.resolve(fileName);
            objectMapper.writeValue(file.toFile(), envelope);

            log.info("Audit envelope saved: {}", file.toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save audit envelope: " + fileName, e);
        }
    }
}
