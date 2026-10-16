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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API Controller cho Tồn kho thành phẩm, Kho nguyên liệu và Công thức chế biến (Giai đoạn 1).
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
        if (scope == null || (!scope.can("INVENTORY_VIEW") && !scope.can("INVENTORY_MANAGE"))) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Bạn không có quyền xem tồn kho.");
            return;
        }

        try {
            String action = request.getParameter("action");
            if ("recipes".equalsIgnoreCase(action)) {
                sendOk(response, inventoryService.allRecipes());
                return;
            }

            long branchId = branchId(scope, request);
            String type = request.getParameter("type");

            if ("material".equalsIgnoreCase(type)) {
                if ("history".equalsIgnoreCase(action)) {
                    long materialId = Long.parseLong(required(request, "materialId"));
                    sendOk(response, inventoryService.materialAdjustmentsOf(scopeBranch(scope), branchId, materialId));
                    return;
                }
                sendOk(response, inventoryService.materialsOfBranch(scopeBranch(scope), branchId));
                return;
            }

            if ("history".equalsIgnoreCase(action)) {
                long productId = Long.parseLong(required(request, "productId"));
                sendOk(response, inventoryService.adjustmentsOf(scopeBranch(scope), branchId, productId));
                return;
            }

            sendOk(response, inventoryService.stockOfBranch(scopeBranch(scope), branchId));
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = scope(request);
        if (scope == null || !scope.can("INVENTORY_MANAGE")) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "Chỉ Admin hoặc Quản lý chi nhánh mới được điều chỉnh tồn kho.");
            return;
        }

        try {
            String action = request.getParameter("action");
            if ("save_recipe".equalsIgnoreCase(action) || "saveRecipe".equalsIgnoreCase(action)) {
                long productId = Long.parseLong(required(request, "productId"));
                String rawItems = request.getParameter("items");
                List<InventoryDAO.RecipeItemInput> itemsList = new ArrayList<>();
                if (rawItems != null && !rawItems.isBlank()) {
                    List<?> parsed = SerializationUtil.fromJson(rawItems, List.class);
                    for (Object obj : parsed) {
                        if (obj instanceof Map<?, ?> map) {
                            long mId = Long.parseLong(String.valueOf(map.get("materialId")));
                            double dosage = Double.parseDouble(String.valueOf(map.get("dosage")));
                            double lossRate = map.get("lossRate") != null
                                    ? Double.parseDouble(String.valueOf(map.get("lossRate")))
                                    : 0.0;
                            String note = map.get("note") != null ? String.valueOf(map.get("note")) : "";
                            String unit = map.get("unit") != null ? String.valueOf(map.get("unit")) : "";
                            itemsList.add(new InventoryDAO.RecipeItemInput(mId, dosage, lossRate, note, unit));
                        }
                    }
                }
                inventoryService.saveRecipe(productId, itemsList);
                sendOk(response, Map.of("status", "SUCCESS", "message", "Đã lưu công thức sản phẩm thành công!"));
                return;
            }

            long branchId = branchId(scope, request);
            String type = request.getParameter("type");
            long actorId = sessionUserId(request);

            if ("produce".equalsIgnoreCase(action)) {
                long productId = Long.parseLong(required(request, "productId"));
                int productQty = Integer.parseInt(required(request, "productQty"));
                String note = request.getParameter("note");
                inventoryService.produce(scopeBranch(scope), branchId, productId, productQty, actorId, note);
                sendOk(response, Map.of(
                        "status", "SUCCESS",
                        "message", "Chế biến bắp nước thành công! Đã tự động cập nhật cả 2 kho."));
                return;
            }

            if ("material".equalsIgnoreCase(type)) {
                long materialId = Long.parseLong(required(request, "materialId"));
                double quantity = Double.parseDouble(required(request, "quantity"));
                String reason = required(request, "reason");
                String note = request.getParameter("note");

                if ("adjust".equalsIgnoreCase(action)) {
                    inventoryService.adjustMaterial(scopeBranch(scope), branchId, materialId, quantity, reason, note, actorId);
                } else {
                    String costPriceRaw = request.getParameter("costPrice");
                    Long costPrice = (costPriceRaw != null && !costPriceRaw.isBlank())
                            ? Long.parseLong(costPriceRaw.trim())
                            : null;
                    String supplier = request.getParameter("supplier");
                    String poNumber = request.getParameter("poNumber");
                    inventoryService.restockMaterial(scopeBranch(scope), branchId, materialId, quantity, reason,
                            costPrice, supplier, poNumber, note, actorId);
                }
                sendOk(response, inventoryService.materialsOfBranch(scopeBranch(scope), branchId));
                return;
            }

            long productId = Long.parseLong(required(request, "productId"));
            int quantity = Integer.parseInt(required(request, "quantity"));
            String reason = request.getParameter("reason");
            if ("adjust".equalsIgnoreCase(action)) {
                inventoryService.adjust(scopeBranch(scope), branchId, productId, quantity, reason, actorId);
            } else {
                inventoryService.restock(scopeBranch(scope), branchId, productId, quantity, reason, actorId);
            }
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

    private long sessionUserId(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            Object value = request.getSession(false).getAttribute("userId");
            if (value instanceof Long l) return l;
            if (value instanceof Integer i) return i.longValue();
        }
        return 1L;
    }

    private String required(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số: " + name);
        }
        return value.trim();
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
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else if (e instanceof NumberFormatException) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST", "Dữ liệu số không hợp lệ.");
        } else {
            e.printStackTrace();
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "Không thể xử lý dữ liệu tồn kho: " + e.getMessage());
        }
    }
}
