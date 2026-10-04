package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import dal.DBContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin/Manager xem wallet transactions toàn hệ thống.
 * GET /admin/wallet/transactions?limit=
 */
public class WalletAdminServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null) {
                sendError(response, 401, "UNAUTHORIZED", "Yêu cầu đăng nhập");
                return;
            }
            if (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER) {
                sendError(response, 403, "FORBIDDEN", "Chỉ Admin/Manager xem được sao kê ví");
                return;
            }
            int limit = parseIntOr(request.getParameter("limit"), 50);
            if (limit <= 0 || limit > 200) limit = 50;

            String sql = "SELECT TOP " + limit + " id, user_id, type, amount, balance_after, ref_type, ref_id, created_at "
                    + "FROM dbo.wallet_tx ORDER BY created_at DESC";
            List<Map<String, Object>> items = new ArrayList<>();
            try (Connection conn = DBContext.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("type", rs.getString("type"));
                    m.put("amount", rs.getLong("amount"));
                    m.put("balanceAfter", rs.getLong("balance_after"));
                    m.put("refType", rs.getString("ref_type"));
                    long refId = rs.getLong("ref_id");
                    m.put("refId", rs.wasNull() ? null : refId);
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    m.put("createdAt", ts == null ? null : ts.toLocalDateTime().toString());
                    items.add(m);
                }
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("items", items);
            resp.put("total", items.size());
            sendOk(response, resp);
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }

    private int parseIntOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (Exception e) { return fallback; }
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
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
