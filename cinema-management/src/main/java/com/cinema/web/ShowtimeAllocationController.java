package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.showtime.ShowtimeAllocation;
import com.cinema.showtime.ShowtimeAllocationDAO;
import com.cinema.showtime.ShowtimeAllocationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API cho phân bổ suất chiếu (Admin → Manager).
 *
 * <ul>
 *   <li>GET    /api/showtime-allocations                — Admin: tất cả; Manager: của branch mình.</li>
 *   <li>GET    /api/showtime-allocations/{id}           — chi tiết.</li>
 *   <li>GET    /api/showtime-allocations/{id}/history   — lịch sử thay đổi.</li>
 *   <li>POST   /api/showtime-allocations                — Admin tạo.</li>
 *   <li>PUT    /api/showtime-allocations/{id}           — Admin sửa số lượng.</li>
 *   <li>DELETE /api/showtime-allocations/{id}           — Admin xóa (chỉ khi created=0).</li>
 * </ul>
 */
public class ShowtimeAllocationController extends HttpServlet {

    private ShowtimeAllocationService service;

    @Override
    public void init() throws ServletException {
        this.service = new ShowtimeAllocationService(new ShowtimeAllocationDAO());
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireScope(req);
            String path = req.getPathInfo() == null ? "" : req.getPathInfo();

            if ("/mine".equals(path)) {
                // Manager view: chỉ branch của mình, tất cả status
                Long branchId = pickBranchFilter(scope, req);
                List<ShowtimeAllocation> items = service.listForScope(scope, branchId,
                        parseLong(req.getParameter("movieId")),
                        req.getParameter("status"));
                sendOk(resp, Map.of("items", items, "total", items.size()));
                return;
            }

            if (path.matches("/\\d+/history")) {
                long id = Long.parseLong(path.split("/")[1]);
                sendOk(resp, Map.of("items", service.listHistory(scope, id), "total", 0));
                return;
            }

            if (path.matches("/\\d+")) {
                long id = Long.parseLong(path.substring(1));
                ShowtimeAllocation a = service.getForScope(scope, id);
                sendOk(resp, a);
                return;
            }

            if (path.isEmpty() || "/".equals(path)) {
                Long branchId = parseLong(req.getParameter("branchId"));
                if (scope.role() == Role.BRANCH_MANAGER) {
                    if (branchId != null && !scope.includesBranch(branchId)) {
                        sendForbidden(resp, "Manager không thể xem phân bổ của chi nhánh khác");
                        return;
                    }
                    if (scope.branchIds().isEmpty()) {
                        sendOk(resp, Map.of("items", List.of(), "total", 0));
                        return;
                    }
                }
                List<ShowtimeAllocation> items = service.listForScope(scope, branchId,
                        parseLong(req.getParameter("movieId")),
                        req.getParameter("status"));
                sendOk(resp, Map.of("items", items, "total", items.size()));
                return;
            }

            sendBadRequest(resp, "Endpoint không hợp lệ");
        } catch (NumberFormatException e) {
            sendBadRequest(resp, "ID không hợp lệ");
        } catch (ServiceException se) {
            sendError(resp, se.httpStatus(), se.code(), se.getMessage());
        } catch (Exception e) {
            getServletContext().log("ShowtimeAllocation GET error", e);
            sendInternalError(resp);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireScope(req);
            String path = req.getPathInfo() == null ? "" : req.getPathInfo();
            if (path.isEmpty() || "/".equals(path)) {
                long movieId = parseLong(req.getParameter("movieId"));
                long branchId = parseLong(req.getParameter("branchId"));
                int qty = Integer.parseInt(req.getParameter("allocatedQuantity"));
                String note = req.getParameter("note");
                ShowtimeAllocation created = service.adminCreate(scope, movieId, branchId,
                        qty, note);
                sendOk(resp, created);
                return;
            }
            sendBadRequest(resp, "Endpoint không hợp lệ");
        } catch (NumberFormatException e) {
            sendBadRequest(resp, "Thiếu hoặc sai định dạng tham số");
        } catch (ServiceException se) {
            sendError(resp, se.httpStatus(), se.code(), se.getMessage());
        } catch (Exception e) {
            getServletContext().log("ShowtimeAllocation POST error", e);
            sendInternalError(resp);
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireScope(req);
            String path = req.getPathInfo() == null ? "" : req.getPathInfo();
            if (path.matches("/\\d+")) {
                long id = Long.parseLong(path.substring(1));
                int qty = Integer.parseInt(req.getParameter("allocatedQuantity"));
                String note = req.getParameter("note");
                ShowtimeAllocation updated = service.adminUpdate(scope, id, qty, note);
                sendOk(resp, updated);
                return;
            }
            sendBadRequest(resp, "Endpoint không hợp lệ");
        } catch (NumberFormatException e) {
            sendBadRequest(resp, "Thiếu hoặc sai định dạng tham số");
        } catch (ServiceException se) {
            sendError(resp, se.httpStatus(), se.code(), se.getMessage());
        } catch (Exception e) {
            getServletContext().log("ShowtimeAllocation PUT error", e);
            sendInternalError(resp);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireScope(req);
            String path = req.getPathInfo() == null ? "" : req.getPathInfo();
            if (path.matches("/\\d+")) {
                long id = Long.parseLong(path.substring(1));
                boolean ok = service.adminDelete(scope, id);
                sendOk(resp, Map.of("deleted", ok));
                return;
            }
            sendBadRequest(resp, "Endpoint không hợp lệ");
        } catch (NumberFormatException e) {
            sendBadRequest(resp, "ID không hợp lệ");
        } catch (ServiceException se) {
            sendError(resp, se.httpStatus(), se.code(), se.getMessage());
        } catch (Exception e) {
            getServletContext().log("ShowtimeAllocation DELETE error", e);
            sendInternalError(resp);
        }
    }

    // ---- helpers ----

    private AccessScope requireScope(HttpServletRequest req) {
        AccessScope scope = (AccessScope) req.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null) throw new ServiceException.Unauthorized("Yêu cầu đăng nhập");
        return scope;
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException.Validation("Tham số số không hợp lệ: " + raw);
        }
    }

    /** Manager: scope chỉ trong branchIds của mình. Admin: dùng query param nếu có. */
    private Long pickBranchFilter(AccessScope scope, HttpServletRequest req) {
        if (scope.role() == Role.ADMIN) return parseLong(req.getParameter("branchId"));
        if (scope.branchIds().isEmpty()) return -1L;
        return scope.branchIds().iterator().next();
    }

    private void sendOk(HttpServletResponse resp, Object data) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data);
        resp.getWriter().write(SerializationUtil.toJson(body));
    }

    private void sendForbidden(HttpServletResponse resp, String msg) throws IOException {
        sendError(resp, 403, "FORBIDDEN", msg);
    }

    private void sendBadRequest(HttpServletResponse resp, String msg) throws IOException {
        sendError(resp, 400, "BAD_REQUEST", msg);
    }

    private void sendInternalError(HttpServletResponse resp) throws IOException {
        sendError(resp, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
    }

    private void sendError(HttpServletResponse resp, int status, String code, String msg)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, msg)));
    }
}
