package org.ikozmin.zenith.fes;

import java.nio.file.Path;

public record FesPackage(
        String personName,
        Path directory,
        Path xmlFile,
        Path signFile
) {
}