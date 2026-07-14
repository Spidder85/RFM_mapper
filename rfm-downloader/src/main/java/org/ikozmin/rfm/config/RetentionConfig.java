package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
/** Настройки хранения скачанных файлов и журнала аудита. */
public final class RetentionConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("KeepAuditDays")
    private Integer keepAuditDays;

    @JsonProperty("KeepDownloadedVersions")
    private Integer keepDownloadedVersions;

    @JsonProperty("KeepProcessedEventDays")
    private Integer keepProcessedEventDays;

    @JsonProperty("KeepFailedEventDays")
    private Integer keepFailedEventDays;

    @JsonProperty("KeepResultEventDays")
    private Integer keepResultEventDays;

    public boolean isEnabled() {
        return enabled;
    }

    public int getKeepAuditDays() {
        return keepAuditDays == null ? 30 : keepAuditDays;
    }

    public int getKeepDownloadedVersions() {
        return keepDownloadedVersions == null ? 10 : keepDownloadedVersions;
    }

    public int getKeepProcessedEventDays() {
        return keepProcessedEventDays == null ? 30 : keepProcessedEventDays;
    }

    public int getKeepFailedEventDays() {
        return keepFailedEventDays == null ? 180 : keepFailedEventDays;
    }

    public int getKeepResultEventDays() {
        return keepResultEventDays == null ? 30 : keepResultEventDays;
    }
}
