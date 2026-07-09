package org.ikozmin.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class EmailConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("SmtpHost")
    private String smtpHost;

    @JsonProperty("SmtpPort")
    private Integer smtpPort;

    @JsonProperty("SmtpUsername")
    private String smtpUsername;

    @JsonProperty("SmtpPassword")
    private String smtpPassword;

    @JsonProperty("UseTls")
    private boolean useTls;

    @JsonProperty("From")
    private String from;

    @JsonProperty("To")
    private List<String> to;

    @JsonProperty("Subject")
    private String subject;

    @JsonProperty("IncludeAttachment")
    private boolean includeAttachment;

    public boolean isEnabled() {
        return enabled;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort == null ? 25 : smtpPort;
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

    public boolean isIncludeAttachment() {
        return includeAttachment;
    }
}
