package com.cinema.filter;

import com.cinema.common.CsrfUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;
import java.util.Set;

/** Blocks state-changing requests without a session-bound CSRF token. */
public final class CsrfFilter implements Filter {

    /** Public paths that accept POST without CSRF (no auth required). */
    private static final Set<String> PUBLIC_POST_PATHS = Set.of(
            "/forgot-password",
            "/forgot-password/verify",
            "/forgot-password/reset",
            "/auth/login",
            "/payment/mock-gateway/callback"
    );

    private static final Set<String> PROTECTED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());

        // Endpoint public (login, register, forgot-password...) không có session
        // → không thể có CSRF token; cho qua filter để controller tự xử lý auth.
        if ("POST".equals(httpRequest.getMethod()) && PUBLIC_POST_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Mock gateway callback đã ký HMAC → CSRF token không cần thiết vì
        // request đã được xác thực bằng checksum ở PaymentService.
        boolean signedPaymentCallback = "/payment/mock-gateway/callback".equals(path);
        if (PROTECTED_METHODS.contains(httpRequest.getMethod())
                && !signedPaymentCallback && !CsrfUtil.matches(httpRequest)) {
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("text/plain;charset=UTF-8");
            httpResponse.getWriter().write("CSRF token không hợp lệ hoặc bị thiếu.");
            return;
        }
        chain.doFilter(request, response);
    }
}
