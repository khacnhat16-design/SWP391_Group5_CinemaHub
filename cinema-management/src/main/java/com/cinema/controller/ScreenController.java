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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/** REST API for managing screens and their seat maps. */
public class ScreenController extends HttpServlet {
    private static final Logger LOGGER = Logger.getLogger(ScreenController.class.getName());
    private ScreenDAO screenDao;
    private ScreenService screenService;

    @Override
    public void init() throws ServletException {
        screenDao = new ScreenDAO();
        screenService = new ScreenService(screenDao, new SeatDAO(), new BranchDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireManager(request, response);
            if (scope == null) return;

            String path = request.getPathInfo();
            if (path == null || path.equals("/")) {
                Long branchId = parseOptionalLong(request.getParameter("branchId"));
                if (branchId != null) {
                    requireBranchAccess(scope, branchId);
                    sendOk(response, screenService.listForBranch(branchScope(scope, branchId), branchId));
                } else if (scope.role() == Role.ADMIN) {
                    sendOk(response, screenService.listAll());
                } else if (scope.branchIds().size() == 1) {
                    long assignedBranchId = scope.branchIds().iterator().next();
                    sendOk(response, screenService.listForBranch(
                            branchScope(scope, assignedBranchId), assignedBranchId));
                } else if (scope.branchIds().isEmpty()) {
                    sendOk(response, List.of());
                } else {
                    sendBadRequest(response, "Chọn chi nhánh được phân công để xem phòng chiếu");
                }
                return;
            }

            String[] parts = path.replaceAll("^/+|/+$", "").split("/");
            if (parts.length == 2 && "seats".equals(parts[1])) {
                long screenId = Long.parseLong(parts[0]);
                Long actorBranchScope = branchScopeForScreen(scope, screenId, response);
                if (actorBranchScope == null && scope.role() != Role.ADMIN) return;
                List<Seat> seats = screenService.seatMap(actorBranchScope, screenId);
                sendOk(response, seats);
                return;
            }
            sendBadRequest(response, "Đường dẫn không hợp lệ — dùng /screen hoặc /screen/{id}/seats");
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
            requireBranchAccess(scope, branchId);

            Screen created = screenService.createScreen(branchScope(scope, branchId), branchId,
                    requireParam(request, "code"), requireParam(request, "name"),
                    Integer.parseInt(requireParam(request, "rowCount")),
                    Integer.parseInt(requireParam(request, "colCount")), null,
                    readSeatLayout(request.getParameter("seatLayout")));
            sendOk(response, created);
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

            String path = request.getPathInfo();
            if (path == null || !path.matches("/\\d+")) {
                sendBadRequest(response, "Đường dẫn không hợp lệ — dùng /screen/{id}");
                return;
            }
            long screenId = Long.parseLong(path.substring(1));
            Long actorBranchScope = branchScopeForScreen(scope, screenId, response);
            if (actorBranchScope == null && scope.role() != Role.ADMIN) return;
            var parameters = FormParameters.readPut(request);
            if ("deactivate".equals(parameters.get("action"))) {
                sendOk(response, screenService.deactivate(actorBranchScope, screenId));
                return;
            }

            Screen current = screenDao.findById(screenId)
                    .orElseThrow(() -> new ServiceException.NotFound("Phòng chiếu không tồn tại"));
            String code = requireParam(parameters.get("code"), "code").toUpperCase(Locale.ROOT);
            if (code.length() > 20) {
                throw new ServiceException.Validation("Mã phòng chiếu tối đa 20 ký tự");
            }
            String name = requireParam(parameters.get("name"), "name");
            var existing = screenDao.findByBranchAndCode(current.branchId(), code);
            if (existing.isPresent() && !existing.get().id().equals(screenId)) {
                throw new ServiceException.Conflict("Mã phòng chiếu đã tồn tại trong chi nhánh");
            }

            int rowCount = Integer.parseInt(requireParam(parameters.get("rowCount"), "rowCount"));
            int colCount = Integer.parseInt(requireParam(parameters.get("colCount"), "colCount"));
            List<ScreenService.SeatLayoutInput> layout = readSeatLayout(parameters.get("seatLayout"));
            Screen updated = screenService.updateSeatMap(actorBranchScope, screenId,
                    rowCount, colCount, layout);
            updated.setCode(code);
            updated.setName(name);
            screenDao.update(updated);
            sendOk(response, updated);
        } catch (NumberFormatException e) {
            sendBadRequest(response, "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private AccessScope requireManager(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || !scope.can("SCREEN_MANAGE")) {
            sendForbidden(response, "Chỉ Branch Manager hoặc Admin mới có thể quản lý phòng chiếu");
            return null;
        }
        return scope;
    }

    private void requireBranchAccess(AccessScope scope, long branchId) {
        if (!scope.includesBranch(branchId)) {
            throw new ServiceException.Forbidden("Không thể quản lý phòng chiếu của chi nhánh khác");
        }
    }

    private Long branchScope(AccessScope scope, long branchId) {
        return scope.role() == Role.ADMIN ? null : branchId;
    }

    private Long branchScopeForScreen(AccessScope scope, long screenId,
                                      HttpServletResponse response) throws Exception {
        Screen screen = screenDao.findById(screenId)
                .orElseThrow(() -> new ServiceException.NotFound("Phòng chiếu không tồn tại"));
        if (!scope.includesBranch(screen.branchId())) {
            sendForbidden(response, "Không thể quản lý phòng chiếu của chi nhánh khác");
            return null;
        }
        return branchScope(scope, screen.branchId());
    }

    private String requireParam(HttpServletRequest request, String name) {
        return requireParam(request.getParameter(name), name);
    }

    private String requireParam(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số bắt buộc: " + name);
        }
        return value.trim();
    }

    private Long parseOptionalLong(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
    }

    private List<ScreenService.SeatLayoutInput> readSeatLayout(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return null;
        try {
            ScreenService.SeatLayoutInput[] layout = SerializationUtil.fromJson(
                    raw, ScreenService.SeatLayoutInput[].class);
            return Arrays.asList(layout);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
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
            return;
        }
        LOGGER.log(Level.SEVERE, "Screen API request failed", e);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(
                new ErrorEnvelope("INTERNAL_ERROR", "Lỗi hệ thống")));
    }
}
