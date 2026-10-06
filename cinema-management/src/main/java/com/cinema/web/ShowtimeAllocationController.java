package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.notification.NotificationDAO;
import com.cinema.notification.NotificationService;
import com.cinema.showtime.ShowtimeAllocation;
import com.cinema.showtime.ShowtimeAllocationDAO;
import com.cinema.showtime.ShowtimeAllocationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin allocation CRUD and branch-scoped manager listing. */
public final class ShowtimeAllocationController extends HttpServlet {
    private ShowtimeAllocationService allocationService;

    @Override
    public void init() throws ServletException {
        allocationService = new ShowtimeAllocationService(new ShowtimeAllocationDAO(),
                new NotificationService(new NotificationDAO()));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            AccessScope scope = requireReader(request, response);
            if (scope == null) return;
            String status = request.getParameter("status");
            Long branchId = parseOptionalLong(request.getParameter("branchId"));
            Long movieId = parseOptionalLong(request.getParameter("movieId"));
            List<ShowtimeAllocation> items = new ArrayList<>();

            if (scope.role() == Role.ADMIN) {
                items = allocationService.list(status, branchId, movieId);
            } else if (branchId != null) {
                if (!scope.includesBranch(branchId)) {
                    sendError(response, 403, "FORBIDDEN", "Không thể xem phân bổ của chi nhánh khác");
                    return;
                }
                items = allocationService.list(status, branchId, movieId);
            } else {
                for (Long scopedBranch : scope.branchIds()) {
                    items.addAll(allocationService.list(status, scopedBranch, movieId));
                }
            }
            sendOk(response, Map.of("items", items));
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Tham số số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            AccessScope scope = requireAdmin(request, response);
            if (scope == null) return;
            long movieId = requiredLong(request, "movieId");
            long branchId = requiredLong(request, "branchId");
            int quantity = requiredInt(request, "allocatedQuantity");
            ShowtimeAllocation allocation = allocationService.create(movieId, branchId, quantity,
                    request.getParameter("note"), scope.userId());
            sendOk(response, allocation);
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            AccessScope scope = requireAdmin(request, response);
            if (scope == null) return;
            long id = pathId(request);
            int quantity = requiredInt(request, "allocatedQuantity");
            ShowtimeAllocation updated = allocationService.update(id, quantity,
                    request.getParameter("note"), scope.userId());
            sendOk(response, updated);
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            if (requireAdmin(request, response) == null) return;
            allocationService.delete(pathId(request));
            sendOk(response, Map.of("deleted", true));
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "ID phân bổ không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private AccessScope requireReader(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()
                || (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER)) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin hoặc quản lý chi nhánh mới được xem phân bổ");
            return null;
        }
        return scope;
    }

    private AccessScope requireAdmin(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.role() != Role.ADMIN) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin mới được quản lý phân bổ suất chiếu");
            return null;
        }
        return scope;
    }

    private static long pathId(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || !path.matches("/\\d+")) {
            throw new NumberFormatException("Missing allocation id");
        }
        return Long.parseLong(path.substring(1));
    }

    private static Long parseOptionalLong(String value) {
        return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
    }

    private static long requiredLong(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation(name + " là bắt buộc");
        }
        return Long.parseLong(value.trim());
    }

    private static int requiredInt(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation(name + " là bắt buộc");
        }
        return Integer.parseInt(value.trim());
    }

    private void handleException(HttpServletResponse response, Exception exception) throws IOException {
        if (exception instanceof ServiceException serviceException) {
            sendError(response, serviceException.httpStatus(), serviceException.code(),
                    serviceException.getMessage());
        } else {
            getServletContext().log("Showtime allocation request failed", exception);
            sendError(response, 500, "INTERNAL_ERROR", "Không thể xử lý phân bổ suất chiếu");
        }
    }

    private void sendOk(HttpServletResponse response, Object body) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(body));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(
                new ErrorEnvelope(code, message)));
    }
}
