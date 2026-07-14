package org.ikozmin.rfm.client;

import org.ikozmin.rfm.exception.RfmApiException;
import org.ikozmin.rfm.exception.RfmAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;

/** Повторяет временно неуспешные операции API с заданной паузой. */
public final class RetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxAttempts;
    private final Duration initialDelay;

    public RetryPolicy(int maxAttempts, Duration initialDelay) {
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
    }

    public <T> T execute(String operation, Callable<T> callable) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                last = e;

                if (!isRetryable(e) || attempt == maxAttempts) {
                    throw e;
                }

                Duration delay = initialDelay.multipliedBy(attempt);
                log.warn(
                        "Retryable failure. operation={}, attempt={}/{}, delayMs={}, error={}",
                        operation,
                        attempt,
                        maxAttempts,
                        delay.toMillis(),
                        e.getMessage()
                );

                sleep(delay);
            } catch (Exception e) {
                throw new IllegalStateException("Unexpected checked exception in retry policy", e);
            }
        }

        throw last == null ? new IllegalStateException("Retry failed without exception") : last;
    }

    public void executeVoid(String operation, Runnable runnable) {
        execute(operation, () -> {
            runnable.run();
            return null;
        });
    }

    private boolean isRetryable(RuntimeException e) {
        if (e instanceof RfmApiException) {
            int status = ((RfmApiException) e).getStatusCode();
            return status == -1 || status == 502 || status == 503 || status == 504;
        }

        if (e instanceof RfmAuthException) {
            String message = e.getMessage();
            return message != null && (
                    message.contains("I/O error")
                            || message.contains("interrupted")
                            || message.contains("handshake")
                            || message.contains("Connection reset")
            );
        }

        return false;
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry sleep interrupted", e);
        }
    }
}
