package org.ikozmin.rfm.service;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.ikozmin.rfm.config.EmailConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

public final class EmailNotificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final EmailConfig config;

    public EmailNotificationService(EmailConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void sendUpdateNotification(
            String catalogType,
            String idXml,
            Path filePath,
            String checksum,
            String oldIdXml
    ) {
        if (!isEnabled()) return;

        try {
            validate();

            String subject = buildSubject(catalogType);
            String body = buildBody(catalogType, idXml, filePath, checksum, oldIdXml);

            sendEmail(subject, body, config.getTo(), filePath);

            log.info("Email notification sent. catalog={}, recipients={}",
                    catalogType,
                    config.getTo().size()
            );
        }  catch (Exception e) {
            log.error("Failed to send email notification: {}", e.getMessage(), e);
        }
    }

    private void sendEmail(String subject, String body, List<String> recipients, Path filePath) throws Exception {
        Session session = createSession();

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getFrom()));

        for (String recipient : recipients) {
            if (!isBlank(recipient)) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient.trim()));
            }
        }

        message.setSubject(subject, "UTF-8");

        if (config.isIncludeAttachment() && filePath != null && Files.exists(filePath)) {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, "UTF-8");

            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.attachFile(filePath.toFile());

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(attachmentPart);

            message.setContent(multipart);
        } else {
            message.setText(body, "UTF-8");
        }

        Transport.send(message);
    }

    private Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", String.valueOf(!isBlank(config.getSmtpUsername())));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isUseTls()));

        if (isBlank(config.getSmtpUsername())) {
            return Session.getInstance(props);
        }

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getSmtpUsername(), config.getSmtpPassword());
            }
        });
    }

    private String buildSubject(String catalogType) {
        if (isBlank(config.getSubject())) {
            return "Обновлен перечень Росфинмониторинга: " + catalogType;
        }

        return config.getSubject() + " [" + catalogType + "]";
    }

    private String buildBody(String catalogType, String idXml, Path filePath, String checksum, String oldIdXml) throws Exception {
        StringBuilder body = new StringBuilder();

        body.append("Здравствуйте.").append(System.lineSeparator());
        body.append(System.lineSeparator());
        body.append("В системе Росфинмониторинга опубликована новая версия перечня.").append(System.lineSeparator());
        body.append("Файл успешно загружен и сохранен.").append(System.lineSeparator());
        body.append(System.lineSeparator());

        body.append("Информация об обновлении").append(System.lineSeparator());
        body.append("Перечень: ").append(displayCatalogName(catalogType)).append(System.lineSeparator());
        body.append("Дата и время загрузки: ").append(formatDateTime(LocalDateTime.now())).append(System.lineSeparator());

        if (filePath != null) {
            body.append("Имя файла: ").append(filePath.toAbsolutePath()).append(System.lineSeparator());

            if (Files.exists(filePath)) {
                body.append("Размер файла: ").append(formatFileSize(Files.size(filePath))).append(System.lineSeparator());
            }
        }

        body.append(System.lineSeparator());
        body.append("Техническая информация").append(System.lineSeparator());
        body.append("Предыдущий idXml: ").append(oldIdXml == null ? "отсутствует" : oldIdXml).append(System.lineSeparator());
        body.append("Новый idXml: ").append(idXml).append(System.lineSeparator());

        if (filePath != null) {
            body.append("Путь к файлу: ").append(filePath.toAbsolutePath()).append(System.lineSeparator());
        }

        if (config.isIncludeFileChecksum() && !isBlank(checksum)) {
            body.append("SHA-256: ").append(checksum).append(System.lineSeparator());
        }

        body.append(System.lineSeparator());
        body.append("Это автоматическое уведомление. Отвечать на него не нужно.").append(System.lineSeparator());

        return body.toString();
    }

    private void validate() {
        if (isBlank(config.getSmtpHost())) {
            throw new IllegalStateException("Notifications.Email.SmtpHost is empty");
        }

        if (isBlank(config.getFrom())) {
            throw new IllegalStateException("Notifications.Email.From is empty");
        }

        if (config.getTo() == null || config.getTo().isEmpty()) {
            throw new IllegalStateException("Notifications.Email.To is empty");
        }
    }

    private String displayCatalogName(String catalogType) {
        if (catalogType == null) {
            return "Неизвестный перечень";
        }

        return switch (catalogType.toLowerCase()) {
            case "te2", "te21" -> "Террористы и экстремисты";
            case "mvk" -> "Решения МВК о замораживании денежных средств";
            case "un" -> "Перечень ООН";
            case "un-rus" -> "Перечень ООН на русском языке";
            default -> catalogType;
        };
    }

    private String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.2f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.2f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
