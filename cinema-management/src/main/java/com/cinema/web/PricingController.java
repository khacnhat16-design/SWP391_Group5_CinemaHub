package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.filter.AuthFilter;
import com.cinema.pricing.PriceRule;
import com.cinema.pricing.PricingService;
import com.cinema.pricing.PriceRuleDAO;
import com.cinema.showtime.ShowtimeDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Read-only price-rule endpoint used by the management dashboard. */
public final class PricingController extends HttpServlet {
    private PricingService pricingService;

    @Override
    public void init() throws ServletException {
        pricingService = new PricingService(new PriceRuleDAO(), new ShowtimeDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.role() != Role.ADMIN) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin mới được xem khung giá.");
            return;
        }
        try {
            sendOk(response, pricingService.listRules());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Không thể tải khung giá.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.role() != Role.ADMIN) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin mới được tạo khung giá.");
            return;
        }
        try {
            PriceRule rule = pricingService.createRule(
                    request.getParameter("seatType"),
                    request.getParameter("dayType"),
                    request.getParameter("timeSlot"),
                    Long.parseLong(request.getParameter("price")));
            sendOk(response, rule);
        } catch (Exception e) {
            sendError(response, e instanceof com.cinema.common.ServiceException se
                    ? se.httpStatus() : 500,
                    e instanceof com.cinema.common.ServiceException se ? se.code() : "INTERNAL_ERROR",
                    e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.role() != Role.ADMIN) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin mới được cập nhật khung giá.");
            return;
        }
        try {
            long id = parseRuleId(request.getPathInfo());
            PriceRule rule = pricingService.updateRulePrice(id,
                    Long.parseLong(request.getParameter("price")));
            sendOk(response, rule);
        } catch (Exception e) {
            sendError(response, e instanceof com.cinema.common.ServiceException se
                    ? se.httpStatus() : 500,
                    e instanceof com.cinema.common.ServiceException se ? se.code() : "INTERNAL_ERROR",
                    e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.role() != Role.ADMIN) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin mới được xóa khung giá.");
            return;
        }
        try {
            String path = request.getPathInfo();
            if (path == null || !path.matches("/(?:rules/)?\\d+")) {
                throw new com.cinema.common.ServiceException.Validation("ID khung giá không hợp lệ");
            }
            pricingService.deleteRule(parseRuleId(path));
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (Exception e) {
            sendError(response, e instanceof com.cinema.common.ServiceException se
                    ? se.httpStatus() : 500,
                    e instanceof com.cinema.common.ServiceException se ? se.code() : "INTERNAL_ERROR",
                    e.getMessage());
        }
    }

    private long parseRuleId(String path) {
        if (path == null || !path.matches("/(?:rules/)?\\d+")) {
            throw new com.cinema.common.ServiceException.Validation("ID khung giá không hợp lệ");
        }
        String id = path.substring(path.lastIndexOf('/') + 1);
        return Long.parseLong(id);
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }
}
