package com.cinema.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormParametersTest {

    @Test
    void readsUrlEncodedPutBodyAndQueryParameters() throws Exception {
        HttpServletRequest request = putRequest(
                "action=deactivate&name=Cinema+Central&address=123+Main+St",
                "phone=0900000000");

        Map<String, String> parameters = FormParameters.readPut(request);

        assertEquals("deactivate", parameters.get("action"));
        assertEquals("Cinema Central", parameters.get("name"));
        assertEquals("123 Main St", parameters.get("address"));
        assertEquals("0900000000", parameters.get("phone"));
    }

    private static HttpServletRequest putRequest(String body, String query) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> "PUT";
                    case "getContentType" -> "application/x-www-form-urlencoded;charset=UTF-8";
                    case "getContentLengthLong" -> (long) bytes.length;
                    case "getQueryString" -> query;
                    case "getInputStream" -> inputStream(bytes);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ServletInputStream inputStream(byte[] bytes) {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
