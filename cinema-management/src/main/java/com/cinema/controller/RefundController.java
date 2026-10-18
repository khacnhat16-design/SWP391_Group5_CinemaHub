package com.cinema.controller;

import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.dao.booking.TicketDAO;
import com.cinema.filter.AuthFilter;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.model.refund.RefundRequest;
import com.cinema.service.booking.BookingService;
import com.cinema.service.notification.EmailService;
import com.cinema.service.refund.RefundService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** REST endpoints for customer refund requests and manager decisions. */
public class RefundController extends HttpServlet {
    private RefundService refundService;

    @Override
    public void init() throws ServletException {
        this.refundService = new RefundService(new BookingService(), new EmailService());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            String path = request.getPathInfo() == null ? "" : request.getPathInfo();
            AccessScope scope = scope(request);
            if ("/mine".equals(path)) {
                long userId = requireRoleUser(request, response, scope, Role.CUSTOMER);
                if (userId == 0) return;
                try (Connection conn = DBContext.getConnection()) {
                    sendOk(response, Map.of("items", mapRequests(
                            refundService.listMine(conn, userId))));
                }
                return;
            }
            if ("/eligible-tickets".equals(path)) {
                long userId = requireRoleUser(request, response, scope, Role.CUSTOMER);
                if (userId == 0) return;
                try (Connection conn = DBContext.getConnection()) {
                    sendOk(response, Map.of("items",
                            refundService.listEligibleTickets(conn, userId)));
                }
                return;
            }
            if ("/console".equals(path)) {
                requireManager(response, scope);
                if (!canManage(scope)) return;
                RefundRequest.Status status = parseStatus(request.getParameter("status"));
                boolean allBranches = scope.role() == Role.ADMIN;
                try (Connection conn = DBContext.getConnection()) {
                    sendOk(response, Map.of("items", mapRequests(refundService.listForConsole(
                            conn, scope.branchIds(), allBranches, status))));
                }
                return;
            }
            if (path.matches("/\\d+")) {
                requireManager(response, scope);
                if (!canManage(scope)) return;
                long id = Long.parseLong(path.substring(1));
                try (Connection conn = DBContext.getConnection()) {
                    RefundRequest refund = refundService.findById(conn, id);
                    if (refund == null) {
                        sendError(response, 404, "NOT_FOUND", "Yêu cầu hoàn tiền không tồn tại");
                        return;
                    }
                    if (scope.role() != Role.ADMIN && !scope.includesBranch(refund.branchId())) {
                        sendError(response, 403, "FORBIDDEN",
                                "Bạn không có quyền với yêu cầu hoàn tiền này");
                        return;
                    }
                    sendOk(response, toJson(refund));
                }
                return;
            }
            sendError(response, 404, "NOT_FOUND", "Endpoint hoàn tiền không tồn tại");
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "ID hoặc trạng thái không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            String path = request.getPathInfo() == null ? "" : request.getPathInfo();
            AccessScope scope = scope(request);
            if ("".equals(path) || "/".equals(path)) {
                long userId = requireRoleUser(request, response, scope, Role.CUSTOMER);
                if (userId == 0) return;
                long ticketId = Long.parseLong(requireParam(request, "ticketId"));
                RefundRequest created = refundService.openRequest(ticketId, userId,
                        request.getParameter("bankName"),
                        request.getParameter("bankAccountName"),
                        request.getParameter("bankAccountNumber"),
                        request.getParameter("reason"));
                sendOk(response, Map.of("id", created.id(), "status", created.status().name()));
                return;
            }
            if (path.matches("/\\d+/(approve|reject|email)")) {
                requireManager(response, scope);
                if (!canManage(scope)) return;
                String[] parts = path.substring(1).split("/");
                long id = Long.parseLong(parts[0]);
                boolean allBranches = scope.role() == Role.ADMIN;
                if ("email".equals(parts[1])) {
                    RefundService.EmailResult result = refundService.resendApprovalEmail(
                            id, scope.branchIds(), allBranches);
                    sendOk(response, Map.of("emailSent", result.sent(),
                            "emailError", result.error() == null ? "" : result.error()));
                    return;
                }
                boolean approve = "approve".equals(parts[1]);
                RefundService.RefundDecision decision = refundService.decide(id,
                        sessionUserId(request), scope.branchIds(), allBranches, approve,
                        request.getParameter("managerNote"));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", decision.request().id());
                result.put("status", decision.status().name());
                result.put("emailSent", decision.emailSent());
                result.put("emailError", decision.emailError());
                sendOk(response, result);
                return;
            }
            sendError(response, 404, "NOT_FOUND", "Endpoint hoàn tiền không tồn tại");
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "ID vé hoặc yêu cầu không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private static AccessScope scope(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        return value instanceof AccessScope accessScope ? accessScope : AccessScope.forGuest();
    }

    private static long requireRoleUser(HttpServletRequest request, HttpServletResponse response,
                                        AccessScope scope, Role role) throws IOException {
        if (scope.role() != role) {
            sendError(response, 403, "FORBIDDEN", "Không có quyền thực hiện thao tác này");
            return 0;
        }
        long userId = sessionUserId(request);
        if (userId <= 0) {
            sendError(response, 401, "UNAUTHORIZED", "Vui lòng đăng nhập");
            return 0;
        }
        return userId;
    }

    private static void requireManager(HttpServletResponse response, AccessScope scope)
            throws IOException {
        if (scope.role() != Role.BRANCH_MANAGER && scope.role() != Role.ADMIN) {
            sendError(response, 403, "FORBIDDEN",
                    "Chỉ Quản lý chi nhánh mới được xử lý yêu cầu hoàn tiền");
        }
    }

    private static boolean canManage(AccessScope scope) {
        return scope.role() == Role.BRANCH_MANAGER || scope.role() == Role.ADMIN;
    }

    private static long sessionUserId(HttpServletRequest request) {
        Object value = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute("userId");
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số " + name);
        }
        return value.trim();
    }

    private static RefundRequest.Status parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return RefundRequest.Status.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ServiceException.Validation("Trạng thái hoàn tiền không hợp lệ");
        }
    }

    private static List<Map<String, Object>> mapRequests(List<RefundRequest> requests) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RefundRequest request : requests) result.add(toJson(request));
        return result;
    }

    private static Map<String, Object> toJson(RefundRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", request.id());
        json.put("ticketId", request.ticketId());
        json.put("ticketCode", request.ticketCode());
        json.put("userId", request.userId());
        json.put("branchId", request.branchId());
        json.put("customerFullName", request.customerFullName());
        json.put("customerPhone", request.customerPhone());
        json.put("customerEmail", request.customerEmail());
        json.put("bankName", request.bankName());
        json.put("bankAccountName", request.bankAccountName());
        json.put("bankAccountNumber", request.bankAccountNumber());
        json.put("showtimeId", request.showtimeId());
        json.put("showtimeStart", request.showtimeStart());
        json.put("ticketAmount", request.ticketAmount());
        json.put("status", request.status() == null ? null : request.status().name());
        json.put("reason", request.reason());
        json.put("managerNote", request.managerNote());
        json.put("decidedAt", request.decidedAt());
        json.put("emailSentAt", request.emailSentAt());
        json.put("createdAt", request.createdAt());
        return json;
    }

    private static void sendOk(HttpServletResponse response, Object body) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(body));
    }

    private static void sendError(HttpServletResponse response, int status, String code,
                                  String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }

    private static void handleException(HttpServletResponse response, Exception error)
            throws IOException {
        if (error instanceof ServiceException serviceError) {
            sendError(response, serviceError.httpStatus(), serviceError.code(),
                    serviceError.getMessage());
        } else {
            sendError(response, 500, "INTERNAL_ERROR",
                    "Không thể xử lý yêu cầu hoàn tiền lúc này");
        }
    }
}
