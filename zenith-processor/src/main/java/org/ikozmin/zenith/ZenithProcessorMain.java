package org.ikozmin.zenith;

import org.ikozmin.common.event.FileEventConsumer;
import org.ikozmin.common.event.ProcessingSummaryStore;
import org.ikozmin.common.event.ZenithImportCompletedEventConsumer;
import org.ikozmin.common.event.ZenithProcessingSummary;
import org.ikozmin.common.notification.*;
import org.ikozmin.zenith.config.ZenithConfig;
import org.ikozmin.zenith.config.ZenithConfigLoader;
import org.ikozmin.zenith.config.ZenithWorkflowMode;
import org.ikozmin.zenith.service.ZenithWorkflowService;
import org.ikozmin.common.event.EventRetentionService;
import org.ikozmin.common.event.EventQueueCompactor;
import org.ikozmin.zenith.client.ZenithApiException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
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
            prepareQueue(config, workflowMode);

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
            prepareQueue(config, workflowMode);
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

            notificationItems.add(new ZenithNotificationItem(
                    claimedEvent.get().event().catalog(),
                    summary
            ));

            consumer.markProcessed(claimedEvent.get());

            return EXIT_OK;
        } catch (Exception e) {
            boolean retryable = isRetryableZenithFailure(e);

            log.error("Zenith registry event failed. eventId={}, catalog={}, retryable={}, error={}",
                    claimedEvent.get().event().eventId(),
                    claimedEvent.get().event().catalog(),
                    retryable,
                    e.getMessage(),
                    e);

            ZenithProcessingSummary failureSummary = createFailureSummary(
                    claimedEvent.get().event().eventId(),
                    retryable
            );
            saveSummary(config, failureSummary);
            notificationItems.add(new ZenithNotificationItem(
                    claimedEvent.get().event().catalog(),
                    failureSummary
            ));

            if (retryable && consumer.markRetryable(claimedEvent.get(), e.getMessage())) {
                return EXIT_EVENT_FAILED;
            }
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
            boolean retryable = isRetryableZenithFailure(e);

            log.error("Zenith check event failed. eventId={}, sourceEventId={}, catalog={}, retryable={}, error={}",
                    claimedEvent.get().event().eventId(),
                    claimedEvent.get().event().sourceEventId(),
                    claimedEvent.get().event().catalog(),
                    retryable,
                    e.getMessage(),
                    e);

            ZenithProcessingSummary failureSummary = createFailureSummary(
                    claimedEvent.get().event().sourceEventId(),
                    retryable
            );
            saveSummary(config, failureSummary);
            notificationItems.add(new ZenithNotificationItem(
                    claimedEvent.get().event().catalog(),
                    failureSummary
            ));

            if (retryable && consumer.markRetryable(claimedEvent.get(), e.getMessage())) {
                return EXIT_EVENT_FAILED;
            }

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
     * Возвращает доступные retry-события в очередь и удаляет устаревшие обновления одного перечня.
     */
    private void prepareQueue(ZenithConfig config, ZenithWorkflowMode workflowMode) {
        EventQueueCompactor compactor = new EventQueueCompactor();

        switch (workflowMode) {
            case FULL, IMPORT_ONLY -> {
                Path eventRootDir = Path.of(config.getEvents().getRegistryUpdatedDirectory());
                FileEventConsumer consumer = new FileEventConsumer(eventRootDir);
                int requeued = consumer.requeueDueRetries();
                int deleted = compactor.keepLatestByCatalog(eventRootDir);

                log.info("RegistryUpdated queue prepared. requeuedRetries={}, deletedObsolete={}",
                        requeued,
                        deleted);
            }
            case CHECK_ONLY -> {
                Path eventRootDir = Path.of(config.getEvents().getCheckDirectory());
                ZenithImportCompletedEventConsumer consumer = new ZenithImportCompletedEventConsumer(eventRootDir);
                int requeued = consumer.requeueDueRetries();
                int deleted = compactor.keepLatestByCatalog(eventRootDir);

                log.info("ZenithImportCompleted queue prepared. requeuedRetries={}, deletedObsolete={}",
                        requeued,
                        deleted);
            }
        }
    }

    /**
     * Определяет, можно ли безопасно повторить событие после ошибки Zenith.
     */
    private boolean isRetryableZenithFailure(Exception exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof ZenithApiException apiException) {
                int status = apiException.status();
                return status == 408 || status == 429 || status >= 500;
            }

            if (current instanceof ConnectException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof UnknownHostException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * Создает безопасный для сотрудника summary ошибки без передачи технического текста API в уведомление.
     */
    private ZenithProcessingSummary createFailureSummary(String eventId, boolean retryable) {
        String message = retryable
                ? "Zenith временно недоступен. Повторная попытка будет выполнена автоматически."
                : "Событие не обработано. Подробности доступны в журнале Zenith.";

        return ZenithProcessingSummary.failed(eventId, message);
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

        NotificationPurpose purpose = workflowMode == ZenithWorkflowMode.IMPORT_ONLY
                ? NotificationPurpose.IMPORT
                : NotificationPurpose.CHECK;
        dispatcher.send(message, purpose);
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
