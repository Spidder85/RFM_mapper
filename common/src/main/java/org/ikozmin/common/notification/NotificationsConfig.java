package org.ikozmin.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
/**
 * Общий блок настроек уведомлений и вложенные настройки каналов.
 */
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
