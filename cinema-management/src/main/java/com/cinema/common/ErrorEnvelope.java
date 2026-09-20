package com.cinema.common;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Envelope lỗi đơn giản {code, message} dùng cho filter/controller trả JSON lỗi. */
public record ErrorEnvelope(String code, String message) {
    public ErrorEnvelope {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    }
}
