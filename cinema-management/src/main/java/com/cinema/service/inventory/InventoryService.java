package com.cinema.service.inventory;

import com.cinema.common.ServiceException;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.inventory.InventoryDAO;
import com.cinema.dao.inventory.ProductDAO;

import java.util.List;
import java.util.logging.Logger;

/** Service quản lý tồn kho (Giai đoạn 1 - Màn 1: M-P1). */
public class InventoryService {
    private static final Logger logger = Logger.getLogger(InventoryService.class.getName());

    private final InventoryDAO inventoryDao;
    private final ProductDAO productDao;
    private final BranchDAO branchDao;

    public InventoryService(InventoryDAO inventoryDao, ProductDAO productDao, BranchDAO branchDao) {
        this.inventoryDao = inventoryDao;
        this.productDao = productDao;
        this.branchDao = branchDao;
    }

    /** Lấy toàn bộ tồn kho bắp nước của chi nhánh (M-P1). */
    public List<InventoryDAO.StockRow> stockOfBranch(Long actorBranchScope, long branchId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return inventoryDao.findByBranch(branchId);
    }

    private void requireBranchScope(Long actorBranchScope, long targetBranchId) {
        if (actorBranchScope != null && actorBranchScope != targetBranchId) {
            throw new ServiceException.Forbidden("Bạn không có quyền thao tác trên chi nhánh khác.");
        }
    }
}
