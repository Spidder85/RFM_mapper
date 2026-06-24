package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ZenithTriggerConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("Command")
    private String command;

    @JsonProperty("TimeoutSeconds")
    private Integer timeoutSeconds;

    public boolean isEnabled() {
        return enabled;
    }

    public String getCommand() {
        return command;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds == null ? 1000 : timeoutSeconds;
    }
}
