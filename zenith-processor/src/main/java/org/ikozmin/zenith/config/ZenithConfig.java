package org.ikozmin.zenith.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ZenithConfig {
    @JsonProperty("Events")
    private Events events;

    @JsonProperty("Zenith")
    private Zenith zenith;

    @JsonProperty("Results")
    private Results results;

    public Events getEvents() {
        return events == null ? new Events() : events;
    }

    public Zenith getZenith() {
        return zenith;
    }

    public Results getResults() {
        return results == null ? new Results() : results;
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

        @JsonProperty("Fes")
        private Fes fes;

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

        public Fes getFes() {
            return fes == null ? new Fes() : fes;
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Import {
        @JsonProperty("Enabled")
        private Boolean enabled;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MassCheck {
        @JsonProperty("Enabled")
        private Boolean enabled;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Report {
        @JsonProperty("Enabled")
        private Boolean enabled;

        @JsonProperty("OutDocType")
        private Integer outDocType;

        @JsonProperty("Filter")
        private Boolean filter;

        @JsonProperty("FilterTemplatePath")
        private String filterTemplatePath;

        @JsonProperty("OutputDirectory")
        private String outputDirectory;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public int getOutDocType() {
            return outDocType == null ? 10217 : outDocType;
        }

        public boolean isFilter() {
            return filter == null || filter;
        }

        public String getFilterTemplatePath() {
            return filterTemplatePath == null || filterTemplatePath.isBlank()
                    ? "config/zenith/podft-report-filter.xml"
                    : filterTemplatePath;
        }

        public String getOutputDirectory() {
            return outputDirectory == null || outputDirectory.isBlank()
                    ? "downloads/zenith-reports"
                    : outputDirectory;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Results {
        @JsonProperty("Directory")
        private String directory;

        public String getDirectory() {
            return directory == null || directory.isBlank()
                    ? "events/registry-updated/results"
                    : directory;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Fes {
        @JsonProperty("OutputDirectory")
        private String outputDirectory;

        public String getOutputDirectory() {
            return outputDirectory == null || outputDirectory.isBlank()
                    ? "downloads/fes-packages"
                    : outputDirectory;
        }
    }
}
