package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class NotificationsConfig {
    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("smtp_host")
    private String smtpHost;

    @JsonProperty("smtp_port")
    private int smtpPort;

    @JsonProperty("smtp_username")
    private String smtpUsername;

    @JsonProperty("smtp_password")
    private String smtpPassword;

    @JsonProperty("use_tls")
    private boolean useTls;

    @JsonProperty("from")
    private String from;

    @JsonProperty("to")
    private List<String> to;

    @JsonProperty("subject")
    private String subject;

    @JsonProperty("include_attachments")
    private boolean includeAttachments;

    @JsonProperty("include_file_checksum")
    private boolean includeFileChecksum;

    public boolean isEnabled() {
        return enabled;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public String getFrom() {
        return from;
    }

    public List<String> getTo() {
        return to;
    }

    public String getSubject() {
        return subject;
    }

    public boolean isIncludeAttachments() {
        return includeAttachments;
    }

    public boolean isIncludeFileChecksum() {
        return includeFileChecksum;
    }
}
