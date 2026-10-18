package com.cinema.service.inventory;

import com.cinema.common.ServiceException;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.inventory.InventoryDAO;
import com.cinema.dao.inventory.ProductDAO;
import com.cinema.model.branch.Branch;
import com.cinema.model.inventory.Product;
import com.cinema.util.TransactionTemplate;

import java.util.List;
import java.util.logging.Logger;

/**
 * Service quản lý tồn kho bắp nước, nguyên liệu thô và công thức chế biến (Giai đoạn 1).
 */
public class InventoryService {
    private static final Logger logger = Logger.getLogger(InventoryService.class.getName());

    private final InventoryDAO inventoryDao;
    private final ProductDAO productDao;
    private final BranchDAO branchDao;
    private final TransactionTemplate tx = new TransactionTemplate();

    public InventoryService(InventoryDAO inventoryDao, ProductDAO productDao, BranchDAO branchDao) {
        this.inventoryDao = inventoryDao;
        this.productDao = productDao;
        this.branchDao = branchDao;
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

    /** Nhập kho thành phẩm (M-P3). */
    public void restock(Long actorBranchScope, long branchId, long productId,
                        int deltaQty, String reason, long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty <= 0) {
            throw new ServiceException.Validation("Số lượng nhập kho phải là số nguyên dương");
        }
        requireActiveProduct(productId);
        requireActiveBranch(branchId);

        String effectiveReason = (reason == null || reason.isBlank()) ? "NHẬP KHO" : reason.trim();
        tx.executeVoid(conn -> {
            inventoryDao.restock(conn, branchId, productId, deltaQty);
            inventoryDao.insertAdjustment(conn, branchId, productId, deltaQty, effectiveReason, actorId);
        });
        logger.info("Restocked product " + productId + " +" + deltaQty + " at branch " + branchId);
    }

    /** Điều chỉnh kiểm kê tồn kho thành phẩm (M-P4). */
    public void adjust(Long actorBranchScope, long branchId, long productId,
                       int deltaQty, String reason, long actorId) throws Exception {
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
                inventoryDao.restock(conn, branchId, productId, deltaQty);
            } else {
                if (!inventoryDao.tryDeduct(conn, branchId, productId, -deltaQty)) {
                    throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                            "Điều chỉnh giảm vượt quá tồn kho hiện tại (không được âm kho)");
                }
            }
            inventoryDao.insertAdjustment(conn, branchId, productId, deltaQty, reason.trim(), actorId);
        });
        logger.info("Adjusted stock product " + productId + " " + (deltaQty > 0 ? "+" : "")
                + deltaQty + " at branch " + branchId + " (" + reason + ")");
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

    /** Nhập kho nguyên liệu (M-R3). deltaQty > 0. */
    public void restockMaterial(Long actorBranchScope, long branchId, long materialId,
                               double deltaQty, String reason, Long costPrice,
                               String supplier, String poNumber, String note,
                               long actorId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        if (deltaQty <= 0) {
            throw new ServiceException.Validation("Số lượng nhập kho nguyên liệu phải lớn hơn 0");
        }
        requireActiveBranch(branchId);

        String effectiveReason = (reason == null || reason.isBlank()) ? "NHAP_HANG_TU_NCC" : reason.trim();
        tx.executeVoid(conn -> {
            inventoryDao.restockMaterial(conn, branchId, materialId, deltaQty, effectiveReason,
                    costPrice, supplier, poNumber, note, actorId);
        });
        logger.info("Restocked material " + materialId + " +" + deltaQty + " at branch " + branchId);
    }

    /** Điều chỉnh tồn kho nguyên liệu (M-R5). */
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
            boolean ok = inventoryDao.adjustMaterial(conn, branchId, materialId, deltaQty, reason.trim(), note, actorId);
            if (!ok) {
                throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                        "Điều chỉnh giảm vượt quá tồn kho hiện tại (không được âm kho)");
            }
        });
        logger.info("Adjusted material stock " + materialId + " " + (deltaQty > 0 ? "+" : "")
                + deltaQty + " at branch " + branchId + " (" + reason + ")");
    }


    // ================================================================
    // HELPER GUARDS
    // ================================================================

    private void requireBranchScope(Long actorBranchScope, long targetBranchId) {
        if (actorBranchScope != null && actorBranchScope != targetBranchId) {
            throw new ServiceException.Forbidden("Vi phạm phạm vi chi nhánh: không thể thao tác trên chi nhánh khác");
        }
    }

    private void requireActiveProduct(long productId) throws Exception {
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));
        if (!product.isActive()) {
            throw new ServiceException.BusinessRule("PRODUCT_INACTIVE",
                    "Sản phẩm đã ngừng kinh doanh — không thể thao tác tồn kho");
        }
    }

    private void requireActiveBranch(long branchId) throws Exception {
        Branch branch = branchDao.findById(branchId)
                .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));
        if (!branch.isActive()) {
            throw new ServiceException.BusinessRule("BRANCH_INACTIVE",
                    "Chi nhánh đã ngừng hoạt động — không thể thao tác");
        }
    }
}
