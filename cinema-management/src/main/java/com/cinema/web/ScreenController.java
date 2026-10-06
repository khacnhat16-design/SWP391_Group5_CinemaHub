package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.branch.BranchDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.FormParameters;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.screen.Screen;
import com.cinema.screen.ScreenDAO;
import com.cinema.screen.ScreenService;
import com.cinema.screen.Seat;
import com.cinema.screen.SeatDAO;
import com.cinema.screen.ScreenService.SeatLayoutInput;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * REST API controller cho phòng chiếu & sơ đồ ghế (Req 3.1-3.6).
 *
 * <p>Access model: Branch Manager quản lý screen của branch mình (Admin toàn chuỗi);
 * Branch Staff/Customer/Guest không có quyền quản lý — chỉ Manager/Admin (Req 3.6).
 */
public class ScreenController extends HttpServlet {
    private ScreenService screenService;

    @Override
    public void init() throws ServletException {
        this.screenService = new ScreenService(new ScreenDAO(), new SeatDAO(), new BranchDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireManager(request, response);
            if (scope == null) return;

            String pathInfo = request.getPathInfo();
            if (pathInfo != null && pathInfo.matches("/\\d+/seats")) {
                long screenId = Long.parseLong(pathInfo.substring(1, pathInfo.length() - "/seats".length()));
                List<Seat> seats = screenService.seatMap(scopeBranch(scope), screenId);
                sendOk(response, seats);
            } else {
                String branchIdRaw = request.getParameter("branchId");
                if (branchIdRaw == null && scope.role() != Role.ADMIN) {
                    if (scope.branchIds().isEmpty()) {
                        sendForbidden(response, "Tài khoản chưa được gán chi nhánh");
                        return;
                    }
                    List<Screen> screens = new java.util.ArrayList<>();
                    for (Long branchId : scope.branchIds()) {
                        screens.addAll(screenService.listForBranch(branchId, branchId));
                    }
                    sendOk(response, screens);
                    return;
                }
                if (branchIdRaw == null) {
                    sendOk(response, screenService.listAll());
                    return;
                }
                long branchId = Long.parseLong(branchIdRaw);
                if (scope.role() != Role.ADMIN && !scope.includesBranch(branchId)) {
                    sendForbidden(response, "Không có quyền truy cập phòng chiếu của chi nhánh này");
                    return;
                }
                List<Screen> screens = screenService.listForBranch(
                        scope.role() == Role.ADMIN ? null : branchId, branchId);
                sendOk(response, screens);
            }
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireManager(request, response);
            if (scope == null) return;

            long branchId = Long.parseLong(requireParam(request, "branchId"));
            String code = request.getParameter("code");
            String name = request.getParameter("name");
            int rowCount = parseIntParam(request, "rowCount");
            int colCount = parseIntParam(request, "colCount");
            String vipRows = request.getParameter("vipRows");
            List<SeatLayoutInput> layout = parseSeatLayout(request.getParameter("seatLayout"));

            Screen screen = screenService.createScreen(scopeBranch(scope), branchId,
                    code, name, rowCount, colCount, vipRows, layout);
            sendOk(response, screen);
        } catch (NumberFormatException e) {
            sendBadRequest(response, "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireManager(request, response);
            if (scope == null) return;

            // Trim "/" cuối nếu có để tránh NumberFormatException khi client gọi
            // /screen/4/ (trailing slash) hoặc /screen/4/xxx (extra path).
            String pathInfo = request.getPathInfo();
            String idPart = (pathInfo == null) ? "" : pathInfo.replaceAll("^/+", "");
            if (idPart.contains("/")) {
                idPart = idPart.substring(0, idPart.indexOf('/'));
            }
            if (idPart.isEmpty()) {
                sendBadRequest(response, "Thiếu ID phòng chiếu");
                return;
            }
            long screenId = Long.parseLong(idPart);
            var parameters = FormParameters.readPut(request);
            String action = parameters.get("action");

            if ("deactivate".equals(action)) {
                sendOk(response, screenService.deactivate(scopeBranch(scope), screenId));
            } else {
                int rowCount = parseIntParam(parameters, "rowCount");
                int colCount = parseIntParam(parameters, "colCount");
                List<SeatLayoutInput> layout = parseSeatLayout(parameters.get("seatLayout"));
                if (layout == null) {
                    sendOk(response, screenService.updateSeatMap(scopeBranch(scope), screenId,
                            rowCount, colCount, parameters.get("vipRows")));
                    return;
                }
                sendOk(response, screenService.updateSeatMap(scopeBranch(scope), screenId,
                        rowCount, colCount, layout));
            }
        } catch (NumberFormatException e) {
            sendBadRequest(response, "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // ---- helpers (mirror MovieController/BranchController patterns) ----

    /** Trả scope nếu là Branch Manager/Admin có quyền SCREEN_MANAGE; ngược lại gửi 403 và trả null. */
    private AccessScope requireManager(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || !scope.can("SCREEN_MANAGE")) {
            sendForbidden(response, "Chỉ Branch Manager hoặc Admin mới có thể quản lý phòng chiếu");
            return null;
        }
        return scope;
    }

    /** Admin không bị ép scope (null); Manager ép đúng branch duy nhất trong scope. */
    private Long scopeBranch(AccessScope scope) {
        if (scope.role() == Role.ADMIN) return null;
        return scope.branchIds().isEmpty() ? -1L : scope.branchIds().iterator().next();
    }

    private String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số bắt buộc: " + name);
        }
        return value.trim();
    }

    private int parseIntParam(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số bắt buộc: " + name);
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException.Validation(name + " phải là số nguyên");
        }
    }

    private int parseIntParam(java.util.Map<String, String> parameters, String name) {
        String raw = parameters.get(name);
        if (raw == null || raw.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số bắt buộc: " + name);
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException.Validation(name + " phải là số nguyên");
        }
    }

    private List<SeatLayoutInput> parseSeatLayout(String json) throws Exception {
        if (json == null || json.isBlank()) return null;
        try {
            SeatLayoutInput[] layout = SerializationUtil.fromJson(json, SeatLayoutInput[].class);
            return java.util.Arrays.asList(layout);
        } catch (Exception e) {
            throw new ServiceException.Validation("Dữ liệu sơ đồ ghế không hợp lệ");
        }
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope("FORBIDDEN", message)));
    }

    private void sendBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope("BAD_REQUEST", message)));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            response.setStatus(service.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(SerializationUtil.toJson(
                    new ErrorEnvelope(service.code(), service.getMessage())));
        } else {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(SerializationUtil.toJson(
                    new ErrorEnvelope("INTERNAL_ERROR", "Lỗi hệ thống")));
        }
    }
}
