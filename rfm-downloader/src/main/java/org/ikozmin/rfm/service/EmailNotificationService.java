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
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.common.notification.NotificationSender;
import org.ikozmin.rfm.config.EmailConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class EmailNotificationService implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final EmailConfig config;

    public EmailNotificationService(EmailConfig config) {
        this.config = config;
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public void send(NotificationMessage message) {
        if (!isEnabled()) {
            return;
        }

        try {
            validate();

            Path attachment = message.attachments().isEmpty()
                    ? null
                    : message.attachments().getFirst();

            sendEmail(message.subject(), message.body(), config.getTo(), attachment);

            log.info("Email notification sent. recipients={}", config.getTo().size());
        } catch (Exception e) {
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
