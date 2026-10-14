package com.cinema.controller;

import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.inventory.InventoryDAO;
import com.cinema.dao.inventory.ProductDAO;
import com.cinema.filter.AuthFilter;
import com.cinema.model.auth.AccessScope;
import com.cinema.service.inventory.InventoryService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * REST API Controller cho Tồn kho bắp nước (Giai đoạn 1 - Màn 1: M-P1).
 */
@WebServlet(name = "inventoryController", urlPatterns = { "/inventory", "/inventory/*" })
public class InventoryController extends HttpServlet {
    private InventoryService inventoryService;

    @Override
    public void init() throws ServletException {
        this.inventoryService = new InventoryService(new InventoryDAO(), new ProductDAO(), new BranchDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = scope(request);
        if (scope == null || scope.isGuest()) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Bạn không có quyền xem tồn kho.");
            return;
        }

        try {
            long branchId = branchId(scope, request);
            sendOk(response, inventoryService.stockOfBranch(scopeBranch(scope), branchId));
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private AccessScope scope(HttpServletRequest request) {
        return (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
    }

    private Long scopeBranch(AccessScope scope) {
        if (scope == null || scope.role() == null) return null;
        return scope.role().name().equals("ADMIN")
                ? null
                : scope.branchIds().stream().findFirst().orElse(-1L);
    }

    private long branchId(AccessScope scope, HttpServletRequest request) {
        Long scoped = scopeBranch(scope);
        if (scoped != null && scoped > 0) {
            return scoped;
        }
        String raw = request.getParameter("branchId");
        if (raw != null && !raw.isBlank()) {
            return Long.parseLong(raw.trim());
        }
        return scope.branchIds().stream().findFirst()
                .orElseThrow(() -> new ServiceException.Validation("Hãy chọn chi nhánh."));
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ErrorEnvelope envelope = new ErrorEnvelope(code, message);
        response.getWriter().write(SerializationUtil.toJson(envelope));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException.Validation) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST", e.getMessage());
        } else if (e instanceof ServiceException.Forbidden) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", e.getMessage());
        } else if (e instanceof ServiceException.NotFound) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND", e.getMessage());
        } else {
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Lỗi xử lý tồn kho: " + e.getMessage());
        }
    }
}
