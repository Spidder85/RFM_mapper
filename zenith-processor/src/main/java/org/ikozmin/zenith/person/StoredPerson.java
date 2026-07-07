package org.ikozmin.zenith.person;

import java.time.LocalDate;

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
