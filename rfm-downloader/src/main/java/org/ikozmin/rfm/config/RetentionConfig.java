package org.ikozmin.rfm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class RetentionConfig {
    @JsonProperty("Enabled")
    private boolean enabled;

    @JsonProperty("KeepAuditDays")
    private Integer keepAuditDays;

    @JsonProperty("KeepDownloadedVersions")
    private Integer keepDownloadedVersions;

    public boolean isEnabled() {
        return enabled;
    }

    public int getKeepAuditDays() {
        return keepAuditDays == null ? 30 : keepAuditDays;
    }

    public int getKeepDownloadedVersions() {
        return keepDownloadedVersions == null ? 10 : keepDownloadedVersions;
    }
}
