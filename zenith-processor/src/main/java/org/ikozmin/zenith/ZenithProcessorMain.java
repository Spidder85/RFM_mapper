package org.ikozmin.zenith;

import org.ikozmin.common.event.FileEventConsumer;
import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithImportCompletedEventConsumer;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.NotificationDispatcher;
import org.ikozmin.common.notification.NotificationMessage;
import org.ikozmin.common.notification.ZenithNotificationItem;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.config.ZenithConfigLoader;
import org.ikozmin.zenith.config.ZenithWorkflowMode;
import org.ikozmin.common.notification.ZenithNotificationTextBuilder;
import org.ikozmin.zenith.service.ZenithWorkflowService;
import org.ikozmin.common.event.EventRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "zenith-processor",
        mixinStandardHelpOptions = true,
        description = "Processes registry update events and imports them into Zenith"
)
/**
 * Точка входа zenith-processor: читает события, запускает workflow и сохраняет итог обработки.
 */
public final class ZenithProcessorMain implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(ZenithProcessorMain.class);
    private static final int EXIT_OK = 0;
    private static final int EXIT_PROGRAM_ERROR = 1;
    private static final int EXIT_EVENT_FAILED = 2;
    private static final int EXIT_NO_EVENTS = 3;

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

    @Option(names = "--suppress-notification", description = "Do not send Zenith notification for this run")
    private boolean suppressNotification;

    /** Запускает CLI и завершает процесс с кодом выполненной команды. */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new ZenithProcessorMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    /** Загружает конфигурацию и запускает выбранный режим обработки событий. */
    public Integer call() {
        try {
            ZenithConfig config = new ZenithConfigLoader().load(configPath);
            ZenithWorkflowMode workflowMode = resolveMode(config);

            if (watch) {
                runWatch(config, workflowMode);
                return 0;
            }

            try {
                if (drain) {
                    return processDrain(config, workflowMode);
                }

                return processOnce(config, workflowMode);
            } finally {
                applyEventRetention(config);
            }
        } catch (Exception e) {
            log.error("Zenith processor failed: {}", e.getMessage(), e);
            System.err.println("Zenith processor failed: " + e.getMessage());
            return EXIT_PROGRAM_ERROR;
        }
    }

    /** Бесконечно опрашивает очередь с заданной в конфигурации периодичностью. */
    private void runWatch(ZenithConfig config, ZenithWorkflowMode workflowMode) throws InterruptedException {
        Duration delay = Duration.ofSeconds(config.getWorkflow().getPollIntervalSeconds());

        log.info("Zenith processor started in watch mode. mode={}, delay={}",
                workflowMode,
                delay);

        while (!Thread.currentThread().isInterrupted()) {
            int exitCode = processOnce(config, workflowMode);

            if (exitCode != EXIT_OK && exitCode != EXIT_NO_EVENTS) {
                log.warn("Zenith watch iteration finished with non-zero code: {}", exitCode);
            }

            applyEventRetention(config);

            Thread.sleep(delay.toMillis());
        }
    }

    /**
     * Обрабатывает всю доступную очередь и отправляет одно уведомление по ее итогам.
     */
    private Integer processDrain(ZenithConfig config, ZenithWorkflowMode workflowMode) {
        int processed = 0;
        int failed = 0;
        List<ZenithNotificationItem> notificationItems = new ArrayList<>();

        try {
            while (true) {
                int exitCode = processOnce(config, workflowMode, false, notificationItems);

                if (exitCode == EXIT_NO_EVENTS) {
                    log.info("Zenith drain completed. processedEvents={}, failedEvents={}", processed, failed);
                    return EXIT_OK;
                }

                if (exitCode == EXIT_EVENT_FAILED) {
                    failed++;
                    continue;
                }

                if (exitCode != EXIT_OK) {
                    return exitCode;
                }

                processed++;
            }
        } finally {
            sendNotificationIfNeeded(config, workflowMode, notificationItems);
        }
    }

    /**
     * Обрабатывает одно событие и отправляет уведомление только по результату этой итерации.
     */
    private Integer processOnce(ZenithConfig config, ZenithWorkflowMode workflowMode) {
        List<ZenithNotificationItem> notificationItems = new ArrayList<>();
        int exitCode = processOnce(config, workflowMode, requireEvent, notificationItems);
        sendNotificationIfNeeded(config, workflowMode, notificationItems);
        return exitCode;
    }

    /**
     * Маршрутизирует одну итерацию в нужную очередь и передает накопитель результатов.
     */
    private Integer processOnce(
            ZenithConfig config,
            ZenithWorkflowMode workflowMode,
            boolean requireEventForIteration,
            List<ZenithNotificationItem> notificationItems
    ) {
        return switch (workflowMode) {
            case FULL -> processRegistryUpdatedEvent(
                    config,
                    ZenithWorkflowMode.FULL,
                    requireEventForIteration,
                    notificationItems
            );
            case IMPORT_ONLY -> processRegistryUpdatedEvent(
                    config,
                    ZenithWorkflowMode.IMPORT_ONLY,
                    requireEventForIteration,
                    notificationItems
            );
            case CHECK_ONLY -> processImportCompletedEvent(
                    config,
                    requireEventForIteration,
                    notificationItems
            );
        };
    }

    /** Берет событие обновления реестра и выполняет импорт либо полный workflow. */
    private Integer processRegistryUpdatedEvent(
            ZenithConfig config,
            ZenithWorkflowMode workflowMode,
            boolean requireEventForIteration,
            List<ZenithNotificationItem> notificationItems
    ) {
        FileEventConsumer consumer = new FileEventConsumer(
                Path.of(config.getEvents().getRegistryUpdatedDirectory())
        );

        if (retryFailed) {
            Optional<Path> requeued = consumer.requeueOldestFailed();

            if (requeued.isEmpty()) {
                log.info("No failed registry update events found");
                return EXIT_OK;
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

            //if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
                notificationItems.add(new ZenithNotificationItem(
                        claimedEvent.get().event().catalog(),
                        summary
                ));
            //}

            consumer.markProcessed(claimedEvent.get());

            return EXIT_OK;
        } catch (Exception e) {
            log.error("Zenith registry event failed. eventId={}, catalog={}, error={}",
                    claimedEvent.get().event().eventId(),
                    claimedEvent.get().event().catalog(),
                    e.getMessage(),
                    e);

            ZenithProcessingSummary failureSummary = ZenithProcessingSummary.failed(
                    claimedEvent.get().event().eventId(),
                    "Ошибка обработки события Zenith: " + e.getMessage()
            );
            saveSummary(config, failureSummary);

            //if (workflowMode != ZenithWorkflowMode.IMPORT_ONLY) {
                notificationItems.add(new ZenithNotificationItem(
                        claimedEvent.get().event().catalog(),
                        failureSummary
                ));
            //}
            consumer.markFailed(claimedEvent.get());

            return EXIT_EVENT_FAILED;
        }
    }

    /** Берет офисное событие после импорта и выполняет только массовую проверку. */
    private Integer processImportCompletedEvent(
            ZenithConfig config,
            boolean requireEventForIteration,
            List<ZenithNotificationItem> notificationItems
    ) {
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
            notificationItems.add(new ZenithNotificationItem(
                    claimedEvent.get().event().catalog(),
                    summary
            ));
            consumer.markProcessed(claimedEvent.get());

            return EXIT_OK;
        } catch (Exception e) {
            log.error("Zenith check event failed. eventId={}, sourceEventId={}, catalog={}, error={}",
                    claimedEvent.get().event().eventId(),
                    claimedEvent.get().event().sourceEventId(),
                    claimedEvent.get().event().catalog(),
                    e.getMessage(),
                    e);

            ZenithProcessingSummary failureSummary = ZenithProcessingSummary.failed(
                    claimedEvent.get().event().sourceEventId(),
                    "Ошибка обработки события Zenith: " + e.getMessage()
            );
            saveSummary(config, failureSummary);
            notificationItems.add(new ZenithNotificationItem(
                    claimedEvent.get().event().catalog(),
                    failureSummary
            ));
            consumer.markFailed(claimedEvent.get());

            return EXIT_EVENT_FAILED;
        }
    }

    /** Возвращает единый код отсутствия события и при необходимости сообщает о нарушении require-event. */
    private int noEvent(boolean requireEventForIteration) {
        if (requireEventForIteration) {
            log.error("No events found, but event is required");
            System.err.println("No events found, but event is required");
            return EXIT_NO_EVENTS;
        }

        log.info("No events found");
        return EXIT_NO_EVENTS;
    }

    /** Сохраняет summary, который затем использует RFM или самостоятельное уведомление Zenith. */
    private void saveSummary(ZenithConfig config, ZenithProcessingSummary summary) {
        ProcessingSummaryStore summaryStore = new ProcessingSummaryStore(
                Path.of(config.getResults().getDirectory())
        );

        Path summaryFile = summaryStore.save(summary);

        log.info("Zenith summary saved: {}", summaryFile.toAbsolutePath());
    }

    /** Выбирает режим из CLI, а при его отсутствии - из конфигурации. */
    private ZenithWorkflowMode resolveMode(ZenithConfig config) {
        if (mode != null && !mode.isBlank()) {
            return ZenithWorkflowMode.from(mode);
        }

        return config.getWorkflow().getMode();
    }

    /**
     * Отправляет одно итоговое уведомление в соответствии с режимом выполненного workflow.
     */
    private void sendNotificationIfNeeded(
            ZenithConfig config,
            ZenithWorkflowMode workflowMode,
            List<ZenithNotificationItem> notificationItems
    ) {
        if (notificationItems == null || notificationItems.isEmpty()) {
            return;
        }

        if (suppressNotification) {
            log.info("Zenith notification is suppressed by command line option");
            return;
        }

        NotificationDispatcher dispatcher = new NotificationDispatcher(config.getNotifications());

        if (!dispatcher.isEnabled()) {
            return;
        }

        ZenithNotificationTextBuilder textBuilder  = new ZenithNotificationTextBuilder();

        NotificationMessage message = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
                ? textBuilder.buildImport(notificationItems)
                : textBuilder.buildCheck(notificationItems);

        dispatcher.send(message);
    }

    /** Очищает завершенные события всех очередей, доступных данному экземпляру Zenith. */
    private void applyEventRetention(ZenithConfig config) {
        EventRetentionService retentionService = new EventRetentionService();

        retentionService.apply(Path.of(config.getEvents().getRegistryUpdatedDirectory()));

        for (String directory : config.getEvents().getImportCompletedDirectories()) {
            retentionService.apply(Path.of(directory));
        }

        String checkDirectory = config.getEvents().getCheckDirectory();

        if (checkDirectory != null && !checkDirectory.isBlank()) {
            retentionService.apply(Path.of(checkDirectory));
        }
    }
}
