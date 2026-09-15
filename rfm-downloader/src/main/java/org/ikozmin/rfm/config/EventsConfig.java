package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
/** Настройки файловой очереди событий об обновленных реестрах. */
public final class EventsConfig {
    @JsonProperty("Directory")
    private String directory;

    public String getDirectory() {
        return directory == null || directory.isBlank()
                ? "events/registry-updated"
                : directory;
    }
}
