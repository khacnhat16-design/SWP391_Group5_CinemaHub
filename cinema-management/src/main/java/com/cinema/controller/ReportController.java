package com.cinema.controller;

import com.cinema.model.branch.Branch;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.service.report.ReportService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API controller cho báo cáo doanh thu & vận hành (Req 14.1-14.5).
 *
 * <p>Access: Admin (toàn chuỗi, lọc branch/phim) và Branch Manager (chỉ chi nhánh mình,
 * quyền REPORT_VIEW). Branch scope ép từ AccessScope — không tin branchId từ client
 * khi là Manager (Req 14.2).
 *
 * <p>GET /report/revenue|daily|movies|operations?from=&to=&branchId=&movieId=&page=&pageSize=
 */
public class ReportController extends HttpServlet {
    private ReportService reportService;

    @Override
    public void init() throws ServletException {
        this.reportService = new ReportService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || !scope.can("REPORT_VIEW")) {
                sendForbidden(response, "Chỉ Admin hoặc Branch Manager mới được xem báo cáo");
                return;
            }

            LocalDate from = parseDate(request.getParameter("from"));
            LocalDate to = parseDate(request.getParameter("to"));
            if (from == null) from = LocalDate.now().minusDays(30);
            if (to == null) to = LocalDate.now().plusDays(1);
            // Req 14.2 — Manager ép chi nhánh mình; Admin mới được truyền branchFilter
            Long branchScope = scope.role() == Role.ADMIN ? null : requireSingleBranch(scope);
            Long branchFilter = scope.role() == Role.ADMIN
                    ? parseOptionalLong(request.getParameter("branchId")) : null;
            Long movieFilter = scope.role() == Role.ADMIN
                    ? parseOptionalLong(request.getParameter("movieId")) : null;
            int page = parseIntOr(request.getParameter("page"), 0);
            int pageSize = parseIntOr(request.getParameter("pageSize"), 0);

            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            switch (pathInfo) {
                case "/revenue" -> sendOk(response,
                        reportService.revenue(from, to, branchScope, branchFilter, movieFilter));
                case "/daily" -> sendOk(response,
                        reportService.dailyBreakdown(from, to, branchScope, branchFilter, page, pageSize));
                case "/movies" -> sendOk(response,
                        reportService.movieBreakdown(from, to, branchScope, branchFilter, page, pageSize));
                case "/products" -> sendOk(response,
                        reportService.concessionProductBreakdown(from, to, branchScope, branchFilter));
                case "/orders" -> sendOk(response,
                        reportService.concessionOrderList(from, to, branchScope, branchFilter, pageSize));
                case "/summary" -> {
                    // Req 14.1/14.5 — gói tổng hợp một lần cho màn hình báo cáo
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("revenue", reportService.revenue(from, to, branchScope, branchFilter, movieFilter));
                    payload.put("movies", reportService.movieBreakdown(from, to, branchScope, branchFilter, 0, 200));
                    payload.put("products", reportService.concessionProductBreakdown(from, to, branchScope, branchFilter));
                    payload.put("orders", reportService.concessionOrderList(from, to, branchScope, branchFilter, 200));
                    payload.put("daily", reportService.dailyBreakdown(from, to, branchScope, branchFilter, 0, 100));
                    sendOk(response, payload);
                }
                default -> sendError(response, 400, "BAD_REQUEST",
                        "Dùng /report/revenue, /report/daily, /report/movies, /report/products, /report/orders hoặc /report/summary");
            }
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu số không hợp lệ");
        } catch (DateTimeParseException e) {
            sendError(response, 400, "BAD_REQUEST", "Định dạng ngày không hợp lệ (yyyy-MM-dd)");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // ---- helpers (mirror ShiftController patterns) ----

    private long requireSingleBranch(AccessScope scope) {
        if (scope.branchIds().isEmpty()) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        return scope.branchIds().iterator().next();
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return LocalDate.parse(raw.trim());
    }

    private Long parseOptionalLong(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
    }

    private int parseIntOr(String raw, int fallback) {
        return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        sendError(response, 403, "FORBIDDEN", message);
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }
}
