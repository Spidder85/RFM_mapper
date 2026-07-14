package org.ikozmin.zenith.person;

import java.time.LocalDate;

/** Запись локальной базы найденного лица с указанием реестра и статуса черновика ФЭС. */
public record StoredPerson(
        String catalog,
        String personKey,
        String displayName,
        String normalizedName,
        String accountNumber,
        LocalDate firstFoundDate,
        LocalDate lastFoundDate,
        boolean fesPrepared,
        boolean fesSent
) {
}
