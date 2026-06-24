package org.ikozmin.zenith;

import org.ikozmin.common.event.FileEventConsumer;
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
    private Path configPath = Path.of("zenith-config.json");

    @Option(names = "--once", description = "Process one event and exit")
    private boolean once;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ZenithProcessorMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            ZenithConfig config = new ZenithConfigLoader().load(configPath);

            FileEventConsumer consumer = new FileEventConsumer(Path.of(config.getEvents().getDirectory()));
            ZenithWorkflowService workflowService = new ZenithWorkflowService(config);

            Optional<FileEventConsumer.ClaimedEvent> claimedEvent = consumer.claimNext();

            if (claimedEvent.isEmpty()) {
                log.info("No registry update events found");
                return 0;
            }

            try {
                workflowService.process(claimedEvent.get().event());
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
