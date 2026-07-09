package org.ikozmin.zenith;

import org.ikozmin.common.event.FileEventConsumer;
import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithImportCompletedEventConsumer;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationDispatcher;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.config.ZenithConfigLoader;
import org.ikozmin.zenith.config.ZenithWorkflowMode;
import org.ikozmin.zenith.notification.ZenithNotificationTextBuilder;
import org.ikozmin.zenith.service.ZenithWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "zenith-processor",
        mixinStandardHelpOptions = true,
        description = "Processes registry update events and imports them into Zenith"
)
public final class ZenithProcessorMain implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(ZenithProcessorMain.class);

    @Option(names = {"-c", "--config"}, description = "Path to zenith config")
    private Path configPath = Path.of("config", "zenith-config.json");

    @Option(names = "--once", description = "Process one event and exit")
    private boolean once;

    @Option(names = "--drain", description = "Process all currently available events and exit")
    private boolean drain;

    @Option(names = "--watch", description = "Continuously watch event queue")
    private boolean watch;

    @Option(names = "--mode", description = "Workflow mode: FULL, IMPORT_ONLY, CHECK_ONLY")
    private String mode;

    @Option(names = "--require-event", description = "Fail if no event is available")
    private boolean requireEvent;

    @Option(names = "--retry-failed", description = "Move one failed event back to new queue before processing")
    private boolean retryFailed;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ZenithProcessorMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            ZenithConfig config = new ZenithConfigLoader().load(configPath);
            ZenithWorkflowMode workflowMode = resolveMode(config);

            if (watch) {
                runWatch(config, workflowMode);
                return 0;
            }

            if (drain) {
                return processDrain(config, workflowMode);
            }

            return processOnce(config, workflowMode);
        } catch (Exception e) {
            log.error("Zenith processor failed: {}", e.getMessage(), e);
            System.err.println("Zenith processor failed: " + e.getMessage());
            return 1;
        }
    }

    private void runWatch(ZenithConfig config, ZenithWorkflowMode workflowMode) throws InterruptedException {
        Duration delay = Duration.ofSeconds(config.getWorkflow().getPollIntervalSeconds());

        log.info("Zenith processor started in watch mode. mode={}, delay={}",
                workflowMode,
                delay);

        while (!Thread.currentThread().isInterrupted()) {
            int exitCode = processOnce(config, workflowMode);

            if (exitCode != 0 && exitCode != 3) {
                log.warn("Zenith watch iteration finished with non-zero code: {}", exitCode);
            }

            Thread.sleep(delay.toMillis());
        }
    }

    private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
        int processed = 0;

        while (true) {
            int exitCode = processOnce(config, workflowMode, false);

            if (exitCode == 3) {
                log.info("Zenith drain completed. processedEvents={}", processed);
                return 0;
            }

            if (exitCode != 0) {
                return exitCode;
            }

            processed++;
        }
    }

    private Integer processOnce(ZenithConfig config, ZenithWorkflowMode workflowMode) {
        return processOnce(config, workflowMode, requireEvent);
    }

    private Integer processOnce(ZenithConfig config, ZenithWorkflowMode workflowMode, boolean requireEventForIteration) {
        return switch (workflowMode) {
            case FULL -> processRegistryUpdatedEvent(config, ZenithWorkflowMode.FULL, requireEventForIteration);
            case IMPORT_ONLY -> processRegistryUpdatedEvent(config, ZenithWorkflowMode.IMPORT_ONLY, requireEventForIteration);
            case CHECK_ONLY -> processImportCompletedEvent(config, requireEventForIteration);
        };
    }

    private Integer processRegistryUpdatedEvent(
            ZenithConfig config,
            ZenithWorkflowMode workflowMode,
            boolean requireEventForIteration
    ) {
        FileEventConsumer consumer = new FileEventConsumer(
                Path.of(config.getEvents().getRegistryUpdatedDirectory())
        );

        if (retryFailed) {
            Optional<Path> requeued = consumer.requeueOldestFailed();

            if (requeued.isEmpty()) {
                log.info("No failed registry update events found");
                return 0;
            }

            log.info("Failed event requeued: {}", requeued.get().toAbsolutePath());
        }


        ZenithWorkflowService workflowService = new ZenithWorkflowService(config);
        Optional<FileEventConsumer.ClaimedEvent> claimedEvent = consumer.claimNext();

        if (claimedEvent.isEmpty()) {
            return noEvent(requireEventForIteration);
        }

        try {
            ZenithProcessingSummary summary = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
                    ? workflowService.processImportOnly(claimedEvent.get().event())
                    : workflowService.processFull(claimedEvent.get().event());

            saveSummary(config, summary);
            if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
                sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
            }
            consumer.markProcessed(claimedEvent.get());

            return 0;
        } catch (Exception e) {
            consumer.markFailed(claimedEvent.get());
            throw e;
        }
    }

    private Integer processImportCompletedEvent(ZenithConfig config, boolean requireEventForIteration) {
        ZenithImportCompletedEventConsumer consumer = new ZenithImportCompletedEventConsumer(
                Path.of(config.getEvents().getCheckDirectory())
        );

        ZenithWorkflowService workflowService = new ZenithWorkflowService(config);
        Optional<ZenithImportCompletedEventConsumer.ClaimedEvent> claimedEvent = consumer.claimNext();

        if (claimedEvent.isEmpty()) {
            return noEvent(requireEventForIteration);
        }

        try {
            ZenithProcessingSummary summary = workflowService.processCheckOnly(claimedEvent.get().event());
            saveSummary(config, summary);
            sendNotificationIfNeeded(config, claimedEvent.get().event().catalog(), summary);
            consumer.markProcessed(claimedEvent.get());

            return 0;
        } catch (Exception e) {
            consumer.markFailed(claimedEvent.get());
            throw e;
        }
    }

    private int noEvent(boolean requireEventForIteration) {
        if (requireEventForIteration) {
            log.error("No events found, but event is required");
            System.err.println("No events found, but event is required");
            return 3;
        }

        log.info("No events found");
        return 3;
    }

    private void saveSummary(ZenithConfig config, ZenithProcessingSummary summary) {
        ProcessingSummaryStore summaryStore = new ProcessingSummaryStore(
                Path.of(config.getResults().getDirectory())
        );

        Path summaryFile = summaryStore.save(summary);

        log.info("Zenith summary saved: {}", summaryFile.toAbsolutePath());
    }

    private ZenithWorkflowMode resolveMode(ZenithConfig config) {
        if (mode != null && !mode.isBlank()) {
            return ZenithWorkflowMode.from(mode);
        }

        return config.getWorkflow().getMode();
    }

    private void sendNotificationIfNeeded(ZenithConfig config, String catalog, ZenithProcessingSummary summary) {
        NotificationDispatcher dispatcher = new NotificationDispatcher(config.getNotifications());

        if (!dispatcher.isEnabled()) {
            return;
        }

        NotificationMessage message = new ZenithNotificationTextBuilder().build(catalog, summary);
        dispatcher.send(message);
    }
}
