package org.ikozmin.zenith.event;

import java.nio.file.Path;
import java.util.List;

/**
 * Ошибка публикации события после успешного импорта реестра в Zenith.
 *
 * Исключение позволяет отличить ошибку самого импорта от ошибки доставки
 * последующего события в одну из файловых очередей офисов.
 */
public final class ZenithImportEventPublicationException extends RuntimeException {
    private final String failedDirectory;
    private final List<Path> publishedFiles;

    public ZenithImportEventPublicationException(
            String failedDirectory,
            List<Path> publishedFiles,
            Throwable cause
    ) {
        super(
                "Failed to publish Zenith import event to directory: " + failedDirectory,
                cause
        );

        this.failedDirectory = failedDirectory;
        this.publishedFiles = publishedFiles;
    }

    /**
     * Каталог, при публикации в который произошла ошибка.
     */
    public String failedDirectory() {
        return failedDirectory;
    }

    /**
     * Файлы, которые были успешно опубликованы до возникновения ошибки.
     */
    public List<Path> publishedFiles() {
        return publishedFiles;
    }
}
