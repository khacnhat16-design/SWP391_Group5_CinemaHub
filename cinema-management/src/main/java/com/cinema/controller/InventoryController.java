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
        if (scope == null || scope.isGuest()) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Bạn không có quyền truy cập kho hàng.");
            return;
        }

        // Admin không liên quan đến kho hàng chi nhánh
        if (scope.role() == com.cinema.model.auth.Role.ADMIN) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Admin không quản lý kho hàng.");
            return;
        }

        // Chỉ Manager (INVENTORY_MANAGE) và Staff (INVENTORY_VIEW) mới được xem kho
        if (!scope.can("INVENTORY_VIEW") && !scope.can("INVENTORY_MANAGE")) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Bạn không có quyền xem dữ liệu kho hàng.");
            return;
        }

        try {
            String action = request.getParameter("action");

            // 1. Quản lý công thức định mức -> Chỉ Quản lý chi nhánh (Manager) mới có quyền
            if ("recipes".equalsIgnoreCase(action)) {
                if (!scope.can("INVENTORY_MANAGE")) {
                    sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Chỉ Quản lý chi nhánh mới có quyền xem công thức.");
                    return;
                }
                sendOk(response, inventoryService.recipeSummaries());
                return;
            }
            if ("recipe".equalsIgnoreCase(action)) {
                if (!scope.can("INVENTORY_MANAGE")) {
                    sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Chỉ Quản lý chi nhánh mới có quyền xem công thức.");
                    return;
                }
                long productId = Long.parseLong(required(request, "productId"));
                sendOk(response, inventoryService.recipeOfProduct(productId));
                return;
            }

            // 2. Tồn kho và lịch sử chi nhánh (Staff & Manager đều được xem)
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
        } catch (Throwable e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = scope(request);
        if (scope == null || scope.isGuest()) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "Bạn không có quyền thực hiện thao tác kho hàng.");
            return;
        }

        // Admin không liên quan đến kho hàng chi nhánh
        if (scope.role() == com.cinema.model.auth.Role.ADMIN) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "Admin không quản lý thao tác kho hàng.");
            return;
        }

        // Bắt buộc phải có quyền INVENTORY_MANAGE (Chỉ Quản lý chi nhánh)
        if (!scope.can("INVENTORY_MANAGE")) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "Chỉ Quản lý chi nhánh mới có quyền thực hiện thao tác kho hàng (Nhập kho, Điều chỉnh, Sản xuất, Thiết lập công thức).");
            return;
        }

        try {
            String action = request.getParameter("action");
            long actorId = sessionUserId(request);

            // 1. Quản lý công thức định mức (cấp hệ thống) -> Không bắt buộc branchId
            if ("save-recipe".equalsIgnoreCase(action)) {
                long productId = Long.parseLong(required(request, "productId"));
                String itemsJson = required(request, "items");
                List<InventoryDAO.RecipeItemInput> items = parseRecipeItems(itemsJson);
                inventoryService.saveRecipe(productId, items, actorId);
                sendOk(response, Map.of("success", true, "productId", productId, "itemsCount", items.size()));
                return;
            }

            if ("delete-recipe".equalsIgnoreCase(action)) {
                long productId = Long.parseLong(required(request, "productId"));
                inventoryService.deleteRecipe(productId, actorId);
                sendOk(response, Map.of("success", true, "productId", productId));
                return;
            }

            // 2. Thao tác kho chi nhánh (Sản xuất / Điều chỉnh / Nhập kho)
            long branchId = branchId(scope, request);
            String type = request.getParameter("type");

            if ("produce".equalsIgnoreCase(action)) {
                long productId = Long.parseLong(required(request, "productId"));
                int quantity = Integer.parseInt(required(request, "quantity"));
                String note = request.getParameter("note");
                inventoryService.produce(scopeBranch(scope), branchId, productId, quantity, note, actorId);
                sendOk(response, Map.of(
                        "success", true,
                        "message", "Sản xuất thành công " + quantity + " sản phẩm! Đã xuất trừ nguyên liệu và nhập kho thành phẩm.",
                        "productId", productId,
                        "quantity", quantity
                ));
                return;
            }

            if ("material".equalsIgnoreCase(type)) {
                long materialId = Long.parseLong(required(request, "materialId"));
                if ("update-min-stock".equalsIgnoreCase(action)) {
                    double minStock = Double.parseDouble(required(request, "minStock"));
                    inventoryService.updateMaterialMinStock(scopeBranch(scope), materialId, minStock);
                    sendOk(response, Map.of("success", true, "materialId", materialId, "minStock", minStock));
                    return;
                }
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
        } catch (Throwable e) {
            handleException(response, e);
        }
    }

    private AccessScope scope(HttpServletRequest request) {
        return (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
    }

    private Long scopeBranch(AccessScope scope) {
        if (scope == null || scope.role() == null) return null;
        if (scope.role() == com.cinema.model.auth.Role.ADMIN) return null;
        return scope.branchIds().stream().findFirst().orElse(null);
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
        return scope.branchIds().stream().findFirst().orElse(1L);
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

    private void handleException(HttpServletResponse response, Throwable e) throws IOException {
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else if (e instanceof NumberFormatException) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST", "Dữ liệu số không hợp lệ.");
        } else {
            e.printStackTrace();
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "Không thể xử lý dữ liệu tồn kho: " + detail);
        }
    }

    @SuppressWarnings("unchecked")
    private List<InventoryDAO.RecipeItemInput> parseRecipeItems(String json) throws Exception {
        List<Map<String, Object>> rawList = SerializationUtil.fromJson(json, List.class);
        List<InventoryDAO.RecipeItemInput> result = new ArrayList<>();
        if (rawList != null) {
            for (Map<String, Object> map : rawList) {
                long materialId = Long.parseLong(String.valueOf(map.get("materialId")));
                double dosage = Double.parseDouble(String.valueOf(map.get("dosage")));
                double lossRate = map.get("lossRate") != null ? Double.parseDouble(String.valueOf(map.get("lossRate"))) : 0.0;
                String note = map.get("note") != null ? String.valueOf(map.get("note")) : "";
                result.add(new InventoryDAO.RecipeItemInput(materialId, dosage, lossRate, note));
            }
        }
        return result;
    }
}
