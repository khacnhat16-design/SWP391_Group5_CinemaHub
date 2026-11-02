package com.cinema.service.inventory;

import com.cinema.common.ServiceException;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.inventory.InventoryDAO;
import com.cinema.dao.inventory.ProductDAO;
import com.cinema.dao.notification.NotificationDAO;
import com.cinema.model.branch.Branch;
import com.cinema.model.inventory.Product;
import com.cinema.model.notification.Notification;
import com.cinema.util.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Service quản lý tồn kho bắp nước, nguyên liệu thô, công thức chế biến và cảnh báo tồn kho/hạn sử dụng.
 */
public class InventoryService {
    private static final Logger logger = Logger.getLogger(InventoryService.class.getName());

    private final InventoryDAO inventoryDao;
    private final ProductDAO productDao;
    private final BranchDAO branchDao;
    private final NotificationDAO notificationDao;
    private final TransactionTemplate tx = new TransactionTemplate();

    public InventoryService(InventoryDAO inventoryDao, ProductDAO productDao, BranchDAO branchDao, NotificationDAO notificationDao) {
        this.inventoryDao = inventoryDao;
        this.productDao = productDao;
        this.branchDao = branchDao;
        this.notificationDao = notificationDao != null ? notificationDao : new NotificationDAO();
    }

    public InventoryService(InventoryDAO inventoryDao, ProductDAO productDao, BranchDAO branchDao) {
        this(inventoryDao, productDao, branchDao, new NotificationDAO());
    }

    // ================================================================
    // MÀN 1: TỒN KHO THÀNH PHẨM (M-P1 -> M-P4)
    // ================================================================

    /** Tồn kho toàn bộ sản phẩm của chi nhánh (M-P1). */
    public List<InventoryDAO.StockRow> stockOfBranch(Long actorBranchScope, long branchId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findByBranch(branchId);
    }

    /** Tồn kho một sản phẩm tại chi nhánh. */
    public int qtyOf(Long actorBranchScope, long branchId, long productId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findQty(branchId, productId);
    }

    /** Lịch sử biến động của một sản phẩm (M-P2). */
    public List<InventoryDAO.AdjustmentHistoryRow> adjustmentsOf(Long actorBranchScope, long branchId, long productId)
            throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findAdjustments(branchId, productId);
    }

    /** Nhập kho thành phẩm (M-P3) kèm ngày hết hạn và mã lô. */
    public void restock(Long actorBranchScope, long branchId, long productId,
            int deltaQty, String reason, String expiryDate, String batchNo, long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty <= 0) {
            throw new ServiceException.Validation("Số lượng nhập kho phải là số nguyên dương");
        }
        requireActiveProduct(productId);
        requireActiveBranch(branchId);

        String effectiveReason = (reason == null || reason.isBlank()) ? "NHẬP KHO" : reason.trim();
        tx.executeVoid(conn -> {
            inventoryDao.restock(conn, branchId, productId, deltaQty, expiryDate, batchNo);
            inventoryDao.insertAdjustment(conn, branchId, productId, deltaQty, effectiveReason, expiryDate, batchNo, actorId);
        });
        logger.info("Restocked product " + productId + " +" + deltaQty + " at branch " + branchId);
        triggerAsyncAlertCheck(branchId);
    }

    public void restock(Long actorBranchScope, long branchId, long productId,
            int deltaQty, String reason, long actorId) throws Exception {
        restock(actorBranchScope, branchId, productId, deltaQty, reason, null, null, actorId);
    }

    /** Điều chỉnh kiểm kê tồn kho thành phẩm (M-P4) kèm ngày hết hạn và mã lô. */
    public void adjust(Long actorBranchScope, long branchId, long productId,
            int deltaQty, String reason, String expiryDate, String batchNo, long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty == 0) {
            throw new ServiceException.Validation("Chênh lệch điều chỉnh phải khác 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException.Validation("Lý do điều chỉnh là bắt buộc");
        }
        requireActiveProduct(productId);

        tx.executeVoid(conn -> {
            if (deltaQty > 0) {
                inventoryDao.restock(conn, branchId, productId, deltaQty, expiryDate, batchNo);
            } else {
                if (!inventoryDao.tryDeduct(conn, branchId, productId, -deltaQty)) {
                    throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                            "Điều chỉnh giảm vượt quá tồn kho hiện tại (không được âm kho)");
                }
            }
            inventoryDao.insertAdjustment(conn, branchId, productId, deltaQty, reason.trim(), expiryDate, batchNo, actorId);
        });
        logger.info("Adjusted stock product " + productId + " " + (deltaQty > 0 ? "+" : "")
                + deltaQty + " at branch " + branchId + " (" + reason + ")");
        triggerAsyncAlertCheck(branchId);
    }

    public void adjust(Long actorBranchScope, long branchId, long productId,
            int deltaQty, String reason, long actorId) throws Exception {
        adjust(actorBranchScope, branchId, productId, deltaQty, reason, null, null, actorId);
    }

    /** Cấu hình ngưỡng tồn kho tối thiểu cho sản phẩm (Row 60: Configure low-stock thresholds). */
    public void updateProductMinStock(Long actorBranchScope, long branchId, long productId, int minStock) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (minStock < 0) {
            throw new ServiceException.Validation("Định mức tồn tối thiểu không được âm");
        }
        requireActiveProduct(productId);
        inventoryDao.updateProductMinStock(branchId, productId, minStock);
        logger.info("Updated min stock for product " + productId + " at branch " + branchId + " to " + minStock);
        triggerAsyncAlertCheck(branchId);
    }

    // ================================================================
    // MÀN 2: KHO NGUYÊN LIỆU THÔ (M-R1 -> M-R5)
    // ================================================================

    /** Tồn kho nguyên liệu tại chi nhánh (M-R1). */
    public List<InventoryDAO.MaterialStockRow> materialsOfBranch(Long actorBranchScope, long branchId)
            throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findMaterialsByBranch(branchId);
    }

    /** Lịch sử biến động nguyên liệu tại chi nhánh (M-R2). */
    public List<InventoryDAO.MaterialAdjustmentRow> materialAdjustmentsOf(Long actorBranchScope, long branchId,
            long materialId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findMaterialAdjustments(branchId, materialId);
    }

    /** Nhập kho nguyên liệu (M-R3) kèm ngày hết hạn và mã lô. deltaQty > 0. */
    public void restockMaterial(Long actorBranchScope, long branchId, long materialId,
            double deltaQty, String reason, Long costPrice,
            String supplier, String poNumber, String note,
            String expiryDate, String batchNo,
            long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty <= 0) {
            throw new ServiceException.Validation("Số lượng nhập kho nguyên liệu phải lớn hơn 0");
        }
        requireActiveBranch(branchId);

        String effectiveReason = (reason == null || reason.isBlank()) ? "NHAP_HANG_TU_NCC" : reason.trim();
        tx.executeVoid(conn -> {
            inventoryDao.restockMaterial(conn, branchId, materialId, deltaQty, effectiveReason,
                    costPrice, supplier, poNumber, note, expiryDate, batchNo, actorId);
        });
        logger.info("Restocked material " + materialId + " +" + deltaQty + " at branch " + branchId);
        triggerAsyncAlertCheck(branchId);
    }

    public void restockMaterial(Long actorBranchScope, long branchId, long materialId,
            double deltaQty, String reason, Long costPrice,
            String supplier, String poNumber, String note,
            long actorId) throws Exception {
        restockMaterial(actorBranchScope, branchId, materialId, deltaQty, reason, costPrice, supplier, poNumber, note, null, null, actorId);
    }

    /** Điều chỉnh tồn kho nguyên liệu (M-R5) kèm ngày hết hạn và mã lô. */
    public void adjustMaterial(Long actorBranchScope, long branchId, long materialId,
            double deltaQty, String reason, String note,
            String expiryDate, String batchNo,
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
                    expiryDate, batchNo, actorId);
            if (!ok) {
                throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                        "Điều chỉnh giảm vượt quá tồn kho hiện tại (không được âm kho)");
            }
        });
        logger.info("Adjusted material stock " + materialId + " " + (deltaQty > 0 ? "+" : "")
                + deltaQty + " at branch " + branchId + " (" + reason + ")");
        triggerAsyncAlertCheck(branchId);
    }

    public void adjustMaterial(Long actorBranchScope, long branchId, long materialId,
            double deltaQty, String reason, String note,
            long actorId) throws Exception {
        adjustMaterial(actorBranchScope, branchId, materialId, deltaQty, reason, note, null, null, actorId);
    }

    // ================================================================
    // HELPER GUARDS & CONFIGURATIONS
    // ================================================================

    private void requireBranchScope(Long actorBranchScope, long targetBranchId) {
        if (actorBranchScope != null && actorBranchScope != targetBranchId) {
            throw new ServiceException.Forbidden("Vi phạm phạm vi chi nhánh: không thể thao tác trên chi nhánh khác");
        }
    }

    private Product requireActiveProduct(long productId) throws Exception {
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));
        if (!product.isActive()) {
            throw new ServiceException.BusinessRule("PRODUCT_INACTIVE",
                    "Sản phẩm đã ngừng kinh doanh — không thể thao tác tồn kho");
        }
        return product;
    }

    private void requireActiveBranch(long branchId) throws Exception {
        Branch branch = branchDao.findById(branchId)
                .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));
        if (!branch.isActive()) {
            throw new ServiceException.BusinessRule("BRANCH_INACTIVE",
                    "Chi nhánh đã ngừng hoạt động — không thể thao tác");
        }
    }

    /**
     * Cấu hình ngưỡng tồn kho tối thiểu cho nguyên liệu (Row 60: Configure low-stock thresholds).
     */
    public void updateMaterialMinStock(Long actorBranchScope, long materialId, double minStock) throws Exception {
        if (minStock < 0) {
            throw new ServiceException.Validation("Định mức tồn tối thiểu không được âm");
        }
        boolean ok = inventoryDao.updateMaterialMinStock(materialId, minStock);
        if (!ok) {
            throw new ServiceException.NotFound("Nguyên liệu không tồn tại");
        }
        logger.info("Updated min stock for material " + materialId + " to " + minStock);
        triggerAsyncAlertCheck(actorBranchScope != null ? actorBranchScope : 1L);
    }

    /** Lấy cấu hình số ngày cảnh báo cận hạn (Row 62). */
    public int getExpiryWarningDays() throws Exception {
        return inventoryDao.getExpiryWarningDays();
    }

    /** Cấu hình số ngày cảnh báo cận hạn (Row 62: Configure expiry date alerts). */
    public void updateExpiryWarningDays(int days, long actorId) throws Exception {
        if (days < 1 || days > 365) {
            throw new ServiceException.Validation("Số ngày cảnh báo hạn sử dụng phải từ 1 đến 365 ngày");
        }
        inventoryDao.updateExpiryWarningDays(days, actorId);
        logger.info("Updated expiry warning days to " + days + " by user " + actorId);
    }

    /** Lấy toàn bộ cảnh báo tồn kho và hạn sử dụng của chi nhánh (Row 61, 63, 64). */
    public List<InventoryDAO.InventoryAlertItem> getAlerts(Long actorBranchScope, long branchId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        int warningDays = inventoryDao.getExpiryWarningDays();
        return inventoryDao.findLowStockAndExpiryAlerts(branchId, warningDays);
    }

    /**
     * Quét và tạo thông báo hệ thống cho Quản lý & Nhân viên chi nhánh (Row 61 & 63: Generate low-stock & expiry alerts).
     */
    public Map<String, Object> generateAlertNotifications(Long actorBranchScope, long branchId) throws Exception {
        if (actorBranchScope != null && actorBranchScope != branchId) {
            throw new ServiceException.Forbidden("Vi phạm phạm vi chi nhánh");
        }
        int warningDays = inventoryDao.getExpiryWarningDays();
        List<InventoryDAO.InventoryAlertItem> alerts = inventoryDao.findLowStockAndExpiryAlerts(branchId, warningDays);
        List<Long> staffIds = inventoryDao.findStaffUserIdsForBranch(branchId);

        int sentCount = 0;
        if (!alerts.isEmpty() && !staffIds.isEmpty()) {
            try (java.sql.Connection conn = com.cinema.infrastructure.database.DBContext.getConnection()) {
                for (InventoryDAO.InventoryAlertItem alert : alerts) {
                    String notifType = (alert.alertType().equals("EXPIRED") || alert.alertType().equals("EXPIRING_SOON"))
                            ? Notification.TYPE_INVENTORY_EXPIRY_WARNING
                            : Notification.TYPE_INVENTORY_LOW_STOCK;
                    String title = (alert.alertType().equals("EXPIRED"))
                            ? "Cảnh báo hết hạn: " + alert.itemName()
                            : (alert.alertType().equals("EXPIRING_SOON"))
                            ? "Cảnh báo cận hạn: " + alert.itemName()
                            : "Cảnh báo tồn kho: " + alert.itemName();

                    for (Long userId : staffIds) {
                        try {
                            if (!inventoryDao.hasRecentNotification(conn, userId, notifType, alert.itemName())) {
                                notificationDao.insert(conn, userId, notifType, title, alert.message());
                                sentCount++;
                            }
                        } catch (Exception e) {
                            logger.warning("Không thể gửi thông báo kho cho user " + userId + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warning("Lỗi xử lý gửi thông báo cảnh báo kho: " + e.getMessage());
            }
        }

        return Map.of(
                "totalAlerts", alerts.size(),
                "notificationsSent", sentCount,
                "warningDays", warningDays,
                "alerts", alerts
        );
    }

    private void triggerAsyncAlertCheck(long branchId) {
        new Thread(() -> {
            try {
                generateAlertNotifications(null, branchId);
            } catch (Exception e) {
                logger.warning("Lỗi tự động quét cảnh báo kho: " + e.getMessage());
            }
        }).start();
    }

    // ================================================================
    // GIAI ĐOẠN 3: CÔNG THỨC & SẢN XUẤT
    // ================================================================

    /** Lấy danh sách công thức của toàn bộ sản phẩm (M-X1). */
    public List<InventoryDAO.RecipeProductSummary> recipeSummaries() throws Exception {
        return inventoryDao.findAllRecipeSummaries();
    }

    /** Lấy chi tiết công thức của một sản phẩm. */
    public List<InventoryDAO.RecipeItemRow> recipeOfProduct(long productId) throws Exception {
        return inventoryDao.findRecipesByProduct(productId);
    }

    /** Lưu công thức chế biến cho sản phẩm (M-X2). */
    public void saveRecipe(long productId, List<InventoryDAO.RecipeItemInput> items, long actorId) throws Exception {
        Product product = requireActiveProduct(productId);
        if ("COMBO".equalsIgnoreCase(product.type())) {
            throw new ServiceException.BusinessRule("COMBO_NO_RAW_RECIPE",
                    "Sản phẩm '" + product.name()
                            + "' là COMBO bán lẻ theo bundle (ghép từ các món lẻ đã chế biến), không thể thiết lập định mức từ nguyên liệu thô.");
        }
        if (items == null || items.isEmpty()) {
            throw new ServiceException.Validation("Công thức phải chứa ít nhất 1 nguyên liệu");
        }
        for (InventoryDAO.RecipeItemInput it : items) {
            if (it.materialId() <= 0) {
                throw new ServiceException.Validation("Nguyên liệu không hợp lệ");
            }
            if (it.dosage() <= 0) {
                throw new ServiceException.Validation("Định lượng nguyên liệu phải lớn hơn 0");
            }
            if (it.lossRate() < 0) {
                throw new ServiceException.Validation("Tỷ lệ hao hụt không được âm");
            }
        }

        tx.executeVoid(conn -> {
            inventoryDao.saveRecipe(conn, productId, items);
        });
        logger.info(
                "Saved recipe for product " + productId + " with " + items.size() + " materials by actor " + actorId);
    }

    /** Xóa công thức của sản phẩm. */
    public void deleteRecipe(long productId, long actorId) throws Exception {
        requireActiveProduct(productId);
        tx.executeVoid(conn -> {
            inventoryDao.deleteRecipe(conn, productId);
        });
        logger.info("Deleted recipe for product " + productId + " by actor " + actorId);
    }

    /**
     * Luồng Xuất nguyên liệu / Sản xuất thành phẩm.
     * Đảm bảo tính toàn vẹn Transaction Atomic:
     * - Trừ đồng thời tất cả nguyên liệu trong công thức (kèm tỷ lệ hao hụt).
     * - Nếu bất kỳ nguyên liệu nào không đủ tồn kho -> ném ngoại lệ -> ROLLBACK
     * TOÀN BỘ!
     * - Tăng số lượng thành phẩm tương ứng trong kho chi nhánh.
     * - Ghi nhật ký đầy đủ ở cả 2 bảng material_adjustment và inventory_adjustment.
     */
    public void produce(Long actorBranchScope, long branchId, long productId,
            int quantity, String note, long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (quantity <= 0) {
            throw new ServiceException.Validation("Số lượng sản xuất phải là số nguyên lớn hơn 0");
        }
        requireActiveBranch(branchId);
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));
        if (!product.isActive()) {
            throw new ServiceException.BusinessRule("PRODUCT_INACTIVE", "Sản phẩm đã ngừng kinh doanh");
        }
        if ("COMBO".equalsIgnoreCase(product.type())) {
            throw new ServiceException.BusinessRule("COMBO_CANNOT_PRODUCE",
                    "Combo '" + product.name()
                            + "' là gói bán lẻ đóng gói từ thành phẩm, không thể xuất sản xuất từ nguyên liệu thô.");
        }

        List<InventoryDAO.RecipeItemRow> recipe = inventoryDao.findRecipesByProduct(productId);
        if (recipe == null || recipe.isEmpty()) {
            throw new ServiceException.BusinessRule("NO_RECIPE",
                    "Sản phẩm '" + product.name() + "' chưa được thiết lập công thức sản xuất.");
        }

        String effectiveNote = (note != null && !note.isBlank()) ? note.trim() : "";
        String prodReason = "Sản xuất " + quantity + " " + product.name()
                + (effectiveNote.isEmpty() ? "" : " - " + effectiveNote);

        tx.executeVoid(conn -> {
            // 1. Trừ từng nguyên liệu theo định lượng + % hao hụt
            for (InventoryDAO.RecipeItemRow item : recipe) {
                double eff = item.effectiveDosage();
                double totalNeeded = quantity * eff;
                String unit = item.materialUnit() != null ? item.materialUnit().trim().toLowerCase() : "";
                if (unit.equals("cái") || unit.equals("cai") || unit.equals("ly") || unit.equals("hộp")
                        || unit.equals("hop") || unit.equals("ống") || unit.equals("ong")) {
                    totalNeeded = Math.ceil(totalNeeded);
                } else {
                    totalNeeded = Math.round(totalNeeded * 10000.0) / 10000.0;
                }

                boolean deducted = inventoryDao.tryDeductMaterial(conn, branchId, item.materialId(), totalNeeded);
                if (!deducted) {
                    throw new ServiceException.BusinessRule("MATERIAL_INSUFFICIENT",
                            "Nguyên liệu '" + item.materialName() + "' không đủ trong kho để sản xuất "
                                    + quantity + " " + product.name() + " (Cần: " + String.format("%.2f", totalNeeded)
                                    + " " + item.materialUnit() + "). Toàn bộ lệnh sản xuất đã bị hủy (Rollback)!");
                }
                inventoryDao.insertMaterialAdjustment(conn, branchId, item.materialId(), -totalNeeded,
                        "XUAT_CHE_BIEN", prodReason, actorId);
            }

            // 2. Tăng kho thành phẩm
            inventoryDao.restock(conn, branchId, productId, quantity);
            inventoryDao.insertAdjustment(conn, branchId, productId, quantity,
                    "NHAP_TU_CHE_BIEN - " + prodReason, actorId);
        });

        logger.info("Successfully produced " + quantity + " of product " + productId + " at branch " + branchId);
        triggerAsyncAlertCheck(branchId);
    }
}
