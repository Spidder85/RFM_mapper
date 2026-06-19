package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class NotificationsConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("Email")
    private EmailConfig email;

    @JsonProperty("Telegram")
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
