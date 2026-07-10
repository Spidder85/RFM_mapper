package org.ikozmin.rfm.trigger;

import org.ikozmin.rfm.config.ZenithTriggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class ZenithProcessorTrigger {
    private static final Logger log = LoggerFactory.getLogger(ZenithProcessorTrigger.class);

    private final ZenithTriggerConfig config;

    public ZenithProcessorTrigger(ZenithTriggerConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public void runOnce(boolean suppressNotification) {
        if (!isEnabled()) {
            return;
        }

        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalStateException("ZenithTrigger.Command is empty");
        }

        try {
            Path workingDirectory = resolveWorkingDirectory(config.getWorkingDirectory());
            String command = buildCommand(config.getCommand(), suppressNotification);

            log.info("Starting zenith processor. workingDirectory={}, command={}",
                    workingDirectory,
                    command);

            Process process = new ProcessBuilder(
                    "cmd.exe",
                    "/d",
                    "/c",
                    command
            )
                    .directory(workingDirectory.toFile())
                    .inheritIO()
                    .start();

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Zenith processor timeout: "
                        + Duration.ofSeconds(config.getTimeoutSeconds()));
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException("Zenith processor failed. exitCode=" + process.exitValue());
            }

            log.info("Zenith processor completed successfully");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run zenith processor", e);
        }
    }

    private String buildCommand(String baseCommand, boolean suppressNotification) {
        if (!suppressNotification) {
            return baseCommand;
        }

        if (baseCommand.contains("--suppress-notification")) {
            return baseCommand;
        }

        return baseCommand + " --suppress-notification";
    }

    private Path resolveWorkingDirectory(String value) {
        Path path = Path.of(value);

        if (path.isAbsolute()) {
            return path.normalize();
        }

        String appHome = System.getProperty("app.home");

        if (appHome != null && !appHome.isBlank()) {
            return Path.of(appHome).resolve(path).normalize();
        }

        return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }
}
