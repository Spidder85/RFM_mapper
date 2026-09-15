package org.ikozmin.zenith.fes;

import java.nio.file.Path;

/** Подготовленный черновик пакета ФЭС для одного найденного лица. */
public record FesPackage(
        String personName,
        Path directory,
        Path xmlFile,
        Path signFile
) {
}
