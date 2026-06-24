package org.ikozmin.zenith.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ZenithConfig {
    @JsonProperty("Events")
    private Events events;

    @JsonProperty("Zenith")
    private Zenith zenith;

    public Events getEvents() {
        return events;
    }

    public Zenith getZenith() {
        return zenith;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Events {
        @JsonProperty("Directory")
        private String directory;

        public String getDirectory() {
            return directory == null || directory.isBlank()
                    ? "events/registry-updated"
                    : directory;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Zenith {
        @JsonProperty("BaseUrl")
        private String baseUrl;

        @JsonProperty("UserName")
        private String userName;

        @JsonProperty("Password")
        private String password;

        @JsonProperty("ServerName")
        private String serverName;

        @JsonProperty("Import")
        private Import importConfig;

        @JsonProperty("MassCheck")
        private MassCheck massCheck;

        @JsonProperty("Report")
        private Report report;

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getUserName() {
            return userName;
        }

        public String getPassword() {
            String env = System.getenv("ZENITH_PASSWORD");
            return env == null || env.isBlank() ? password : env;
        }

        public String getServerName() {
            return serverName;
        }

        public Import getImportConfig() {
            return importConfig;
        }

        public MassCheck getMassCheck() {
            return massCheck;
        }

        public Report getReport() {
            return report;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Import {
        @JsonProperty("FileFormat")
        private String fileFormat;

        @JsonProperty("Append")
        private Boolean append;

        public String getFileFormat() {
            return fileFormat == null || fileFormat.isBlank() ? "xml" : fileFormat;
        }

        public boolean isAppend() {
            return append != null && append;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MassCheck {
        @JsonProperty("Subsystem")
        private String subsystem;

        @JsonProperty("EmitentId")
        private String emitentId;

        @JsonProperty("Periodic")
        private Boolean periodic;

        public String getSubsystem() {
            return subsystem;
        }

        public String getEmitentId() {
            return emitentId;
        }

        public boolean isPeriodic() {
            return periodic != null && periodic;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Report {
        @JsonProperty("Enabled")
        private boolean enabled;

        @JsonProperty("OutDocType")
        private int outDocType;

        @JsonProperty("Format")
        private String format;

        @JsonProperty("AssignOutDocNum")
        private boolean assignOutDocNum;

        @JsonProperty("FilterTemplatePath")
        private String filterTemplatePath;

        @JsonProperty("OutputDirectory")
        private String outputDirectory;

        public boolean isEnabled() {
            return enabled;
        }

        public int getOutDocType() {
            return outDocType == 0 ? 38 : outDocType;
        }

        public String getFormat() {
            return format == null || format.isBlank() ? "Xlsx" : format;
        }

        public boolean isAssignOutDocNum() {
            return assignOutDocNum;
        }

        public String getFilterTemplatePath() {
            return filterTemplatePath;
        }

        public String getOutputDirectory() {
            return outputDirectory == null || outputDirectory.isBlank()
                    ? "downloads/zenith-reports"
                    : outputDirectory;
        }
    }
}
