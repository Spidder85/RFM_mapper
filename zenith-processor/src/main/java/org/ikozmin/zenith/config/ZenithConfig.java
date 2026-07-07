package org.ikozmin.zenith.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ZenithConfig {
    @JsonProperty("Events")
    private Events events;

    @JsonProperty("Workflow")
    private Workflow workflow;

    @JsonProperty("Zenith")
    private Zenith zenith;

    @JsonProperty("Results")
    private Results results;

    @JsonProperty("Storage")
    private Storage storage;

    public Events getEvents() {
        return events == null ? new Events() : events;
    }

    public Workflow getWorkflow() {
        return workflow == null ? new Workflow() : workflow;
    }

    public Zenith getZenith() {
        return zenith;
    }

    public Results getResults() {
        return results == null ? new Results() : results;
    }

    public Storage getStorage() {
        return storage == null ? new Storage() : storage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Events {
        @JsonProperty("Directory")
        private String directory;

        @JsonProperty("RegistryUpdatedDirectory")
        private String registryUpdatedDirectory;

        @JsonProperty("ImportCompletedDirectories")
        private List<String> importCompletedDirectories;

        @JsonProperty("CheckDirectory")
        private String checkDirectory;

        public String getRegistryUpdatedDirectory() {
            if (registryUpdatedDirectory != null && !registryUpdatedDirectory.isBlank()) {
                return registryUpdatedDirectory;
            }

            return directory == null || directory.isBlank()
                    ? "events/registry-updated"
                    : directory;
        }

        public List<String> getImportCompletedDirectories() {
            if (importCompletedDirectories == null || importCompletedDirectories.isEmpty()) {
                return List.of("events/zenith-imported");
            }

            return importCompletedDirectories;
        }

        public String getCheckDirectory() {
            if (checkDirectory != null && !checkDirectory.isBlank()) {
                return checkDirectory;
            }

            return getImportCompletedDirectories().get(0);
        }

        public String getDirectory() {
            return directory == null || directory.isBlank()
                    ? "events/registry-updated"
                    : directory;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Workflow {
        @JsonProperty("Mode")
        private String mode;

        @JsonProperty("PollIntervalSeconds")
        private Integer pollIntervalSeconds;

        public String getMode() {
            return mode;
        }

        public int getPollIntervalSeconds() {
            return pollIntervalSeconds == null ? 60 : pollIntervalSeconds;
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

        @JsonProperty("Reports")
        private Map<String, Report> reports;

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

        public Report getReport(String catalog) {
            if (report != null && catalog != null) {
                Report reportByCatalog = reports.get(catalog.toLowerCase());

                if (reportByCatalog != null) {
                    return reportByCatalog;
                }
            }
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

        @JsonProperty("FileFormat")
        private String fileFormat;

        @JsonProperty("Append")
        private Boolean append;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public String getFileFormat() {
            return fileFormat == null || fileFormat.isBlank()
                    ? "TerroristsXml"
                    : fileFormat;
        }

        public boolean isAppend() {
            return append != null && append;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MassCheck {
        @JsonProperty("Enabled")
        private Boolean enabled;

        @JsonProperty("Periodic")
        private Boolean periodic;

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public boolean isPeriodic() {
            return periodic != null && periodic;
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

        @JsonProperty("FileNamePrefix")
        private String fileNamePrefix;

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

        public String getFileNamePrefix() {
            return fileNamePrefix == null || fileNamePrefix.isBlank()
                    ? "T38"
                    : fileNamePrefix;
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
    public static final class Storage {
        @JsonProperty("FoundPersonsFile")
        private String foundPersonsFile;

        public String getFoundPersonsFile() {
            return foundPersonsFile == null || foundPersonsFile.isBlank()
                    ? "data/zenith-found-persons.tsv"
                    : foundPersonsFile;
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
