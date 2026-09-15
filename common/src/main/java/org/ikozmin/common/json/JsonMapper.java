package org.ikozmin.common.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Единая настройка Jackson для чтения и записи JSON во всех модулях.
 */
public final class JsonMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private JsonMapper(){}

    public static ObjectMapper get() {
        return MAPPER;
    }
}
