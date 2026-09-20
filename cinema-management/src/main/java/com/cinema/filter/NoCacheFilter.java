package com.cinema.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Tắt cache cho các trang HTML render động (JSP, console, booking, discovery)
 * — tránh browser cache phiên cũ (header không render lại khi role/session đổi).
 *
 * <p>Áp dụng cho mọi request KHÔNG thuộc {@code /assets/} và KHÔNG phải REST API
 * (path /api/...). Vì các asset đã có query-string version, và API trả JSON thì
 * browser không cần cache.</p>
 */
public final class NoCacheFilter implements Filter {
    private static final Logger LOG = Logger.getLogger(NoCacheFilter.class.getName());

    @Override
    public void init(FilterConfig filterConfig) {
        LOG.info("NoCacheFilter initialized — JSP/console/booking pages will set no-store headers");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            String uri = httpRequest.getRequestURI();
            String ctx = httpRequest.getContextPath();
            String path = ctx.isEmpty() ? uri : uri.substring(ctx.length());
            if (!path.startsWith("/assets/") && !path.startsWith("/api/")
                    && !path.endsWith(".js") && !path.endsWith(".css")) {
                httpResponse.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                httpResponse.setHeader("Pragma", "no-cache");
                httpResponse.setDateHeader("Expires", 0);
            }
        }
        chain.doFilter(request, response);
    }
}
