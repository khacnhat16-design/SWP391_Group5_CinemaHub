package com.cinema.common;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Reads URL-encoded form parameters from PUT requests, which Servlet containers need not parse. */
public final class FormParameters {
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private FormParameters() { }

    public static Map<String, String> readPut(HttpServletRequest request) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parseEncoded(request.getQueryString(), parameters);

        String contentType = request.getContentType();
        if (contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                throw new ServiceException.Validation("Dữ liệu biểu mẫu vượt quá giới hạn cho phép");
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new ServiceException.Validation("Dữ liệu biểu mẫu vượt quá giới hạn cho phép");
            }
            parseEncoded(new String(body, StandardCharsets.UTF_8), parameters);
        }
        return parameters;
    }

    private static void parseEncoded(String encoded, Map<String, String> parameters) {
        if (encoded == null || encoded.isEmpty()) return;
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            parameters.putIfAbsent(key, value);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ServiceException.Validation("Dữ liệu biểu mẫu không hợp lệ");
        }
    }
}
