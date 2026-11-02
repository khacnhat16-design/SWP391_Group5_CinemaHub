package com.cinema.service.concession;

import com.cinema.dao.concession.InventoryDAO;
import com.cinema.dao.concession.ProductDAO;
import com.cinema.model.branch.Branch;
import com.cinema.model.concession.Product;

import com.cinema.dao.branch.BranchDAO;
import com.cinema.common.ServiceException;
import com.cinema.util.TransactionTemplate;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Service quản lý tồn kho chi nhánh (Req 19.4-19.7).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Nhập kho: sản phẩm thuộc danh mục Admin (tồn tại + ACTIVE), số lượng nguyên
 *       dương, chi nhánh ACTIVE; tăng tồn kho + ghi bản ghi nhập (Req 19.4).</li>
 *   <li>Kiểm kê/điều chỉnh: bắt buộc lý do, ghi chênh lệch delta (Req 19.5).</li>
 *   <li>qty == 0 → hết hàng tại chi nhánh, chặn bán cả online/offline (Req 19.6 —
 *       guard nằm ở ConcessionService khi trừ kho).</li>
 *   <li>Branch scope: Manager chỉ truy vấn/điều chỉnh kho chi nhánh mình (Req 19.7).</li>
 * </ul>
 */
public class InventoryService {
    private static final Logger logger = Logger.getLogger(InventoryService.class.getName());
    private static final Set<String> VALID_TYPES = Set.of(
            Product.TYPE_POPCORN, Product.TYPE_DRINK, Product.TYPE_COMBO, Product.TYPE_OTHER);

    private final InventoryDAO inventoryDao;
    private final ProductDAO productDao;
    private final BranchDAO branchDao;
    private final TransactionTemplate tx = new TransactionTemplate();

    public InventoryService(InventoryDAO inventoryDao, ProductDAO productDao, BranchDAO branchDao) {
        this.inventoryDao = inventoryDao;
        this.productDao = productDao;
        this.branchDao = branchDao;
    }

    /** Nhập kho (Req 19.4): deltaQty > 0, lý do tùy chọn ("NHẬP KHO" mặc định). */
    public void restock(Long actorBranchScope, long branchId, long productId,
                        int deltaQty, String reason, long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty <= 0) {
            throw new ServiceException.Validation("Số lượng nhập kho phải là số nguyên dương");
        }
        requireActiveProduct(productId);
        requireActiveBranch(branchId);

        String effectiveReason = reason == null || reason.isBlank() ? "NHẬP KHO" : reason.trim();
        tx.executeVoid(conn -> {
            inventoryDao.restock(conn, branchId, productId, deltaQty);
            inventoryDao.insertAdjustment(conn, branchId, productId, deltaQty, effectiveReason, actorId);
        });
        logger.info("Restocked product " + productId + " +" + deltaQty + " at branch " + branchId);
    }

    /**
     * Kiểm kê/điều chỉnh tồn kho (Req 19.5): deltaQty có thể âm (hư hỏng, sai lệch
     * kiểm đếm, hủy hàng) — bắt buộc lý do. Không cho điều chỉnh vượt quá tồn kho
     * hiện tại (không âm kho).
     */
    public void adjust(Long actorBranchScope, long branchId, long productId,
                       int deltaQty, String reason, long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty == 0) {
            throw new ServiceException.Validation("Chênh lệch điều chỉnh phải khác 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException.Validation(
                    "Lý do điều chỉnh là bắt buộc (hư hỏng, sai lệch kiểm đếm, hủy hàng...)");
        }
        requireActiveProduct(productId);

        tx.executeVoid(conn -> {
            if (deltaQty > 0) {
                inventoryDao.restock(conn, branchId, productId, deltaQty);
            } else {
                // Trừ guarded — không cho kho âm kể cả khi điều chỉnh
                if (!inventoryDao.tryDeduct(conn, branchId, productId, -deltaQty)) {
                    throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                            "Điều chỉnh giảm vượt quá tồn kho hiện tại");
                }
            }
            inventoryDao.insertAdjustment(conn, branchId, productId, deltaQty, reason.trim(), actorId);
        });
        logger.info("Adjusted stock product " + productId + " " + (deltaQty > 0 ? "+" : "")
                + deltaQty + " at branch " + branchId + " (" + reason + ")");
    }

    /** Tồn kho chi nhánh (Req 19.6, 19.7) — ép branch scope. */
    public List<InventoryDAO.StockRow> stockOfBranch(Long actorBranchScope, long branchId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findByBranch(branchId);
    }

    /** Lịch sử biến động tồn kho của 1 sản phẩm tại 1 chi nhánh (M-P2). */
    public List<InventoryDAO.AdjustmentRow> adjustmentHistory(Long actorBranchScope, long branchId,
                                                              long productId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findAdjustmentHistory(branchId, productId);
    }

    /** Tồn kho một sản phẩm tại chi nhánh (0 = hết hàng). */
    public int qtyOf(Long actorBranchScope, long branchId, long productId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findQty(branchId, productId);
    }

    // ==========================================
    // RAW MATERIAL METHODS (GIAI ĐOẠN 2)
    // ==========================================

    /** Tồn kho nguyên liệu tại chi nhánh (M-R1). */
    public List<InventoryDAO.MaterialStockRow> materialsOfBranch(Long actorBranchScope, long branchId)
            throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findMaterialsByBranch(branchId);
    }

    /** Lịch sử biến động nguyên liệu (M-R2). */
    public List<InventoryDAO.MaterialAdjustmentRow> materialAdjustmentsOf(Long actorBranchScope, long branchId,
                                                                          long materialId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findMaterialAdjustments(branchId, materialId);
    }

    /** Nhập kho nguyên liệu (M-R3). */
    public void restockMaterial(Long actorBranchScope, long branchId, long materialId,
                                double deltaQty, String reason, Long costPrice,
                                String supplier, String poNumber, String note,
                                long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty <= 0) {
            throw new ServiceException.Validation("Số lượng nhập kho nguyên liệu phải lớn hơn 0");
        }
        requireActiveBranch(branchId);

        String effectiveReason = reason == null || reason.isBlank() ? "NHAP_HANG_TU_NCC" : reason.trim();
        tx.executeVoid(conn -> {
            inventoryDao.restockMaterial(conn, branchId, materialId, deltaQty, effectiveReason,
                    costPrice, supplier, poNumber, note, actorId);
        });
        logger.info("Restocked material " + materialId + " +" + deltaQty + " at branch " + branchId);
    }

    /** Điều chỉnh tồn nguyên liệu (M-R5) — không cho âm kho. */
    public void adjustMaterial(Long actorBranchScope, long branchId, long materialId,
                               double deltaQty, String reason, String note,
                               long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty == 0) {
            throw new ServiceException.Validation("Chênh lệch điều chỉnh phải khác 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException.Validation("Lý do điều chỉnh nguyên liệu là bắt buộc");
        }

        tx.executeVoid(conn -> {
            boolean ok = inventoryDao.adjustMaterial(conn, branchId, materialId, deltaQty, reason.trim(), note,
                    actorId);
            if (!ok) {
                throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                        "Điều chỉnh giảm vượt quá tồn kho hiện tại (không được âm kho)");
            }
        });
        logger.info("Adjusted material stock " + materialId + " " + (deltaQty > 0 ? "+" : "")
                + deltaQty + " at branch " + branchId + " (" + reason + ")");
    }

    // ==========================================
    // RECIPE & PRODUCTION (GIAI ĐOẠN 3)
    // ==========================================

    /** Toàn bộ công thức định mức. */
    public List<InventoryDAO.RecipeItemRow> allRecipes() throws Exception {
        return inventoryDao.findAllRecipes();
    }

    /**
     * Chế biến bắp nước: kiểm tra branch scope, số lượng > 0, chạy trong transaction
     * (thiếu nguyên liệu → rollback 100%).
     */
    public void produce(Long actorBranchScope, long branchId, long productId,
                        int productQty, long actorId, String note) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (productQty <= 0) {
            throw new ServiceException.Validation("Số lượng thành phẩm chế biến phải lớn hơn 0");
        }
        requireActiveBranch(branchId);
        requireActiveProduct(productId);

        try {
            tx.executeVoid(conn -> {
                inventoryDao.produceProduct(conn, branchId, productId, productQty, actorId, note);
            });
        } catch (IllegalStateException e) {
            throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT", e.getMessage());
        }
        logger.info("Produced product " + productId + " qty=" + productQty + " at branch " + branchId);
    }

    /** Lưu toàn bộ công thức cho 1 sản phẩm (M-X2). */
    public void saveRecipe(long productId, List<InventoryDAO.RecipeItemInput> items) throws Exception {
        requireActiveProduct(productId);
        inventoryDao.saveRecipe(productId, items);
        logger.info("Saved recipe for product " + productId + " itemsCount=" + (items != null ? items.size() : 0));
    }

    // ---- helpers ----

    /** Req 19.7 — Manager chi nhánh A không được đụng kho chi nhánh B. */
    private void requireBranchScope(Long actorBranchScope, long branchId) {
        if (actorBranchScope != null && actorBranchScope != branchId) {
            throw new ServiceException.Forbidden(
                    "Vi phạm phạm vi chi nhánh: không thể thao tác tồn kho của chi nhánh khác");
        }
    }

    private void requireActiveProduct(long productId) throws Exception {
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));
        if (!product.isActive()) {
            throw new ServiceException.BusinessRule("PRODUCT_INACTIVE",
                    "Sản phẩm đã ngừng kinh doanh — không thể nhập kho/điều chỉnh");
        }
    }

    /** Req 19.4 — chi nhánh phải ACTIVE khi nhập kho. */
    private void requireActiveBranch(long branchId) throws Exception {
        var branch = branchDao.findById(branchId)
                .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));
        if (!branch.isActive()) {
            throw new ServiceException.BusinessRule("BRANCH_INACTIVE",
                    "Chi nhánh đã ngừng hoạt động — không thể nhập kho");
        }
    }

    static boolean isValidType(String type) {
        return type != null && VALID_TYPES.contains(type.trim().toUpperCase());
    }
}
