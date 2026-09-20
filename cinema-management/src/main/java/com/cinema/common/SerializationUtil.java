package com.cinema.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Tiện ích serialize JSON chung (Jackson), không lộ chi tiết kỹ thuật khi lỗi. */
public final class SerializationUtil {
    private static final ObjectMapper MAPPER = createMapper();

    private SerializationUtil() { }

    private static ObjectMapper createMapper() {
        SimpleModule dateModule = new SimpleModule();
        dateModule.addSerializer(LocalDate.class,
                new com.fasterxml.jackson.databind.JsonSerializer<LocalDate>() {
                    @Override
                    public void serialize(LocalDate value,
                                          com.fasterxml.jackson.core.JsonGenerator generator,
                                          com.fasterxml.jackson.databind.SerializerProvider provider)
                            throws java.io.IOException {
                        generator.writeString(value.toString());
                    }
                });
        dateModule.addSerializer(LocalDateTime.class,
                new com.fasterxml.jackson.databind.JsonSerializer<LocalDateTime>() {
                    @Override
                    public void serialize(LocalDateTime value,
                                          com.fasterxml.jackson.core.JsonGenerator generator,
                                          com.fasterxml.jackson.databind.SerializerProvider provider)
                            throws java.io.IOException {
                        generator.writeString(value.toString());
                    }
                });
        dateModule.addSerializer(LocalTime.class,
                new com.fasterxml.jackson.databind.JsonSerializer<LocalTime>() {
                    @Override
                    public void serialize(LocalTime value,
                                          com.fasterxml.jackson.core.JsonGenerator generator,
                                          com.fasterxml.jackson.databind.SerializerProvider provider)
                            throws java.io.IOException {
                        generator.writeString(value.toString());
                    }
                });
        return new ObjectMapper()
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .registerModule(dateModule);
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // Không bao giờ để lỗi serialize làm hỏng response — trả envelope tối thiểu.
            return "{\"success\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Đã xảy ra lỗi hệ thống.\"}}";
        }
    }

    /** Deserialize JSON string to object of specified type. */
    public static <T> T fromJson(String json, Class<T> clazz) throws Exception {
        ObjectReader reader = MAPPER.readerFor(clazz);
        return reader.readValue(json);
    }
}
