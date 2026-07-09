package org.ikozmin.common.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class TelegramConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("Token")
    private String token;

    @JsonProperty("ChatIds")
    private List<String> chatIds;

    @JsonProperty("ApiIp")
    private String apiIp;

    public boolean isEnabled() {
        return enabled;
    }

    public String getToken() {
        return token;
    }

    public List<String> getChatIds() {
        return chatIds;
    }

    public String getApiIp() {
        return apiIp;
    }
}