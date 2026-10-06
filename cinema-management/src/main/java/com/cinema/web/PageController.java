package com.cinema.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

/**
 * Điều hướng trang JSP (presentation-only — không chứa business logic).
 *
 * <p>GET /console → forward /WEB-INF/views/console.jsp (giao diện vận hành dùng chung
 * REST API qua assets/js/console.js). Module hiển thị theo role do JS quyết định.
 *
 * <p>GET /console/{domain}/list → forward views/{domain}/list.jsp cho các trang danh mục
 * trong {@link #LIST_PAGES}. Các trang này là shell presentation-only: dữ liệu do
 * assets/js/rest-page.js gọi REST API có sẵn. Controller không đụng Service/DAO,
 * không set dữ liệu nghiệp vụ — chỉ forward theo whitelist.
 */
public class PageController extends HttpServlet {

    /** Domain có trang danh mục tại /console/{domain}/list (whitelist chống path traversal). */
    private static final Set<String> LIST_PAGES = Set.of(
            "branch", "movie", "screen", "report", "shift", "booking", "concession", "notification");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo();
        // /console (không có pathInfo) là URL mà sidebar/header dùng — phải render console.
        String requestUri = request.getRequestURI();
        String consoleUri = request.getContextPath() + "/console";
        if (path == null || path.isEmpty() || path.equals("/") || path.equals("/console")
                || requestUri.equals(consoleUri) || requestUri.endsWith("/console")
                || requestUri.endsWith("/console/")) {
            request.getRequestDispatcher("/WEB-INF/views/console.jsp").forward(request, response);
            return;
        }
        // Pattern /console/{domain}/list map thẳng tới views/{domain}/list.jsp
        // để giữ URL quen thuộc cho các module quản lý (movie, screen, branch...).
        if (path.matches("/[^/]+/list")) {
            String domain = path.substring(1, path.length() - "/list".length());
            if (LIST_PAGES.contains(domain)) {
                request.getRequestDispatcher("/WEB-INF/views/" + domain + "/list.jsp")
                        .forward(request, response);
                return;
            }
        }
        response.sendRedirect(request.getContextPath() + "/");
    }
}
