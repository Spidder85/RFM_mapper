package org.ikozmin.common.event;

import java.time.Instant;

/**
 * Служебные сведения о следующей попытке обработки временно неуспешного события.
 */
public record RetryMetadata(
        int attempts,
        Instant nextAttemptAt,
        String lastError
) {
}
