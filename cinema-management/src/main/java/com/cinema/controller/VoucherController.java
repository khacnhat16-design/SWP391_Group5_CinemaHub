package com.cinema.controller;

import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.model.auth.AccessScope;
import com.cinema.model.voucher.Voucher;
import com.cinema.service.voucher.VoucherService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API voucher don gian:
 *  GET /voucher/lookup?code=XXX&orderTotal=100000  -> xem duoc giam bao nhieu (ai cung goi duoc)
 *  GET /voucher/available                      -> voucher con hieu luc cho khach dat ve
 *  GET /voucher  | GET /api/vouchers              -> danh sach (Admin/Manager)
 *  POST /voucher | POST /api/vouchers            -> tao moi (Admin/Manager)
 *  POST /voucher/{id}/status                     -> khoa/mo (Admin/Manager)
 */
public class VoucherController extends HttpServlet {
    private final VoucherService service = new VoucherService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            String path = path(req);
            if (path.endsWith("/available")) {
                List<Map<String, Object>> available = service.listAvailable().stream()
                        .map(this::toAvailableMap)
                        .collect(Collectors.toList());
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(com.cinema.common.SerializationUtil.toJson(available));
                return;
            }
            // /lookup?code=&orderTotal= : public, de khach xem truoc khi dat ve
            if (path.endsWith("/lookup")) {
                String code = req.getParameter("code");
                long total = parseLong(req.getParameter("orderTotal"), 0);
                ok(resp, toMap(service.preview(code, total)));
                return;
            }
            if (!requireVoucherManager(req, resp)) return;
            // con lai: danh sach
            List<Voucher> list = service.listAll();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", list.stream().map(this::toMap).collect(Collectors.toList()));
            ok(resp, out);
        } catch (Exception e) {
            err(resp, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            if (!requireVoucherManager(req, resp)) return;
            String path = path(req);
            // POST /voucher/{id}/status {status}
            if (path.matches(".*/\\d+/status")) {
                long id = Long.parseLong(path.replaceAll(".*/(\\d+)/status", "$1"));
                Map<String, Object> body = readJson(req);
                service.setStatus(id, String.valueOf(body.getOrDefault("status", "")));
                ok(resp, Map.of("ok", true));
                return;
            }
            // POST /voucher {code, discountType, discountValue, minOrderAmount, validFrom, validTo, maxUses}
            Map<String, Object> body = readJson(req);
            Voucher v = service.create(
                    str(body.get("code")),
                    str(body.get("discountType")),
                    parseLong(body.get("discountValue"), 0),
                    parseLong(body.get("minOrderAmount"), 0),
                    parseDateTime(body.get("validFrom"), false),
                    parseDateTime(body.get("validTo"), true),
                    (int) parseLong(body.get("maxUses"), 0));
            ok(resp, toMap(v));
        } catch (Exception e) {
            err(resp, e);
        }
    }

    // ---------- helpers: giu controller mong, de hoc ----------

    private String path(HttpServletRequest req) {
        String p = req.getPathInfo();
        if (p == null) p = "";
        return p;
    }

    private boolean requireVoucherManager(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        AccessScope scope = (AccessScope) req.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) {
            writeAccessError(resp, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return false;
        }
        if (!scope.can("VOUCHER_MANAGE")) {
            writeAccessError(resp, 403, "FORBIDDEN", "Không có quyền quản lý voucher");
            return false;
        }
        return true;
    }

    private void writeAccessError(HttpServletResponse resp, int status, String code, String message)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(com.cinema.common.SerializationUtil.toJson(
                Map.of("success", false, "error", Map.of("code", code, "message", message))));
    }

    private Map<String, Object> toMap(Voucher v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("code", v.code());
        m.put("discountType", v.discountType());
        m.put("discountValue", v.discountValue());
        m.put("minOrderAmount", v.minOrderAmount());
        m.put("validFrom", String.valueOf(v.validFrom()));
        m.put("validTo", String.valueOf(v.validTo()));
        m.put("maxUses", v.maxUses());
        m.put("usedCount", v.usedCount());
        m.put("status", v.status());
        return m;
    }

    private Map<String, Object> toAvailableMap(Voucher v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", v.code());
        m.put("discountType", v.discountType());
        m.put("discountValue", v.discountValue());
        m.put("minOrderAmount", v.minOrderAmount());
        m.put("validTo", String.valueOf(v.validTo()));
        return m;
    }

    private Map<String, Object> toMap(VoucherService.VoucherPreview p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("discountType", p.discountType());
        m.put("discountValue", p.discountValue());
        m.put("discount", p.discount());
        m.put("payable", p.payable());
        return m;
    }

    private void ok(HttpServletResponse resp, Object data) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(com.cinema.common.SerializationUtil.toJson(Map.of("success", true, "data", data)));
    }

    private void err(HttpServletResponse resp, Exception e) throws IOException {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (e instanceof ServiceException se) {
            code = se.code() != null ? se.code() : code;
            if (e instanceof ServiceException.Validation) status = 400;
            else if (e instanceof ServiceException.NotFound) status = 404;
            else if (e instanceof ServiceException.Conflict) status = 409;
            else if (e instanceof ServiceException.Forbidden) status = 403;
        }
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        String msg = e.getMessage() == null ? "Loi he thong" : e.getMessage();
        resp.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\""
                + msg.replace("\"", "'") + "\"}}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpServletRequest req) throws Exception {
        String json = req.getReader().lines().collect(Collectors.joining("\n"));
        return com.cinema.common.SerializationUtil.fromJson(json, Map.class);
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private long parseLong(Object o, long def) {
        try {
            if (o == null || String.valueOf(o).isBlank()) return def;
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Parse ngày/giờ từ client. Chấp nhận:
     *   "2026-10-30"            (date-only, <input type="date">) → start/end of day
     *   "2026-10-30 14:30"      → 14:30:00
     *   "2026-10-30T14:30:00"   → giữ nguyên
     * Trả về null nếu không parse được (controller sẽ trả lỗi validation).
     */
    static LocalDateTime parseDateTime(Object o, boolean inclusiveEndOfDate) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        String s = String.valueOf(o).trim().replace('T', ' ');
        try {
            // "yyyy-MM-dd" — chỉ có ngày, không có giờ.
            if (s.length() == 10) {
                java.time.LocalDate date = java.time.LocalDate.parse(s);
                return inclusiveEndOfDate
                        ? date.atTime(23, 59, 59, 999_000_000)
                        : date.atStartOfDay();
            }
            // "yyyy-MM-dd HH:mm" — thêm giây cho LocalDateTime.parse.
            if (s.length() == 16) s += ":00";
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
