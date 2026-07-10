package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ZenithTriggerConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("Command")
    private String command;

    @JsonProperty("WorkingDirectory")
    private String workingDirectory;

    @JsonProperty("TimeoutSeconds")
    private Integer timeoutSeconds;

    @JsonProperty("SuppressNotificationWhenRfmNotificationEnabled")
    private Boolean suppressNotificationWhenRfmNotificationEnabled;

    public boolean isEnabled() {
        return enabled;
    }

    public String getCommand() {
        return command;
    }

    public String getWorkingDirectory() {
        return workingDirectory == null || workingDirectory.isBlank()
                ? "."
                : workingDirectory;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds == null ? 1000 : timeoutSeconds;
    }

    public boolean isSuppressNotificationWhenRfmNotificationEnabled() {
        return suppressNotificationWhenRfmNotificationEnabled == null
                || suppressNotificationWhenRfmNotificationEnabled;
    }
}
