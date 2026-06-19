package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class NotificationsConfig {
    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("email")
    private EmailConfig email;

    @JsonProperty("telegram")
    private TelegramConfig telegram;

    public boolean isEnabled() {
        return enabled;
    }

    public EmailConfig getEmail() {
        return email;
    }

    public TelegramConfig getTelegram() {
        return telegram;
    }
}
