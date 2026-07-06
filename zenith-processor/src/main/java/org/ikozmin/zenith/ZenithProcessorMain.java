package org.ikozmin.zenith;

import org.ikozmin.common.event.FileEventConsumer;
import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.config.ZenithConfigLoader;
import org.ikozmin.zenith.service.ZenithWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
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

            FileEventConsumer consumer = new FileEventConsumer(Path.of(config.getEvents().getDirectory()));

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
                if (requireEvent) {
                    log.error("No registry update events found, but event is required");
                    System.err.println("No registry update events found, but event is required");
                    return 3;
                }
                log.info("No registry update events found");
                return 0;
            }

            try {
                ZenithProcessingSummary summary = workflowService.process(claimedEvent.get().event());

                ProcessingSummaryStore summaryStore = new ProcessingSummaryStore(
                        Path.of(config.getResults().getDirectory())
                );

                Path summaryFile = summaryStore.save(summary);

                log.info("Zenith summary saved: {}", summaryFile.toAbsolutePath());

                consumer.markProcessed(claimedEvent.get());
                return 0;
            } catch (Exception e) {
                consumer.markFailed(claimedEvent.get());
                throw e;
            }
        } catch (Exception e) {
            log.error("Zenith processor failed: {}", e.getMessage(), e);
            System.err.println("Zenith processor failed: " + e.getMessage());
            return 1;
        }
    }
}
