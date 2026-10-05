package com.cinema.filter;

import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Returns a consistent response for application exceptions. */
public final class ExceptionMappingFilter implements Filter {
    private static final Logger LOG = Logger.getLogger(ExceptionMappingFilter.class.getName());

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException failure) {
            LOG.log(Level.SEVERE, "Unhandled request exception", failure);
            HttpServletResponse http = (HttpServletResponse) response;
            if (http.isCommitted()) throw failure;
            http.reset();
            if (failure instanceof ServiceException service) {
                http.setStatus(service.httpStatus());
                String accept = ((HttpServletRequest) request).getHeader("Accept");
                if (accept != null && accept.contains("application/json")) {
                    http.setContentType("application/json;charset=UTF-8");
                    http.getWriter().write(SerializationUtil.toJson(
                            new ErrorEnvelope(service.code(), service.getMessage())));
                } else {
                    http.setContentType("text/html;charset=UTF-8");
                    http.getWriter().printf(
                            "<!doctype html><html lang=\"vi\"><head><meta charset=\"UTF-8\">"
                            + "<title>CinemaHub</title></head><body><!-- cinema-layout -->"
                            + "<main><div class=\"flash flash-err\">%s</div></main></body></html>",
                            escape(service.getMessage()));
                }
                return;
            }
            http.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            String accept = ((HttpServletRequest) request).getHeader("Accept");
            if (accept != null && accept.contains("application/json")
                    || ((HttpServletRequest) request).getRequestURI().startsWith("/api/")) {
                http.setContentType("application/json;charset=UTF-8");
                http.getWriter().write(SerializationUtil.toJson(
                        new ErrorEnvelope("INTERNAL_ERROR", "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.")));
            } else {
                http.setContentType("text/html;charset=UTF-8");
                http.getWriter().printf(
                        "<!doctype html><html lang=\"vi\"><head><meta charset=\"UTF-8\">"
                        + "<title>CinemaHub</title></head><body><!-- cinema-layout -->"
                        + "<main><div class=\"flash flash-err\">%s</div></main></body></html>",
                        "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");
            }
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
