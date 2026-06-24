package org.ikozmin.rfm.trigger;

import org.ikozmin.rfm.config.ZenithTriggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    public void runOnce() {
        if (!isEnabled()) {
            return;
        }

        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalStateException("ZenithTrigger.Command is empty");
        }

        try {
            List<String> command = parseCommand(config.getCommand());

            log.info("Starting zenith processor. command={}", command);

            Process process = new ProcessBuilder(command)
                    .inheritIO()
                    .start();

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Zenith processor timeout: " + Duration.ofSeconds(config.getTimeoutSeconds()));
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException("Zenith processor failed. exitCode=" + process.exitValue());
            }

            log.info("Zenith processor completed successfully");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run zenith processor", e);
        }
    }

    private List<String> parseCommand(String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        List<String> result = new ArrayList<>();

        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }

        return result;
    }
}
