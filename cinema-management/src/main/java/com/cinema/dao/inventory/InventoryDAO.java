package com.cinema.dao.inventory;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access cho tồn kho thành phẩm, nguyên liệu thô và công thức chế biến (Giai đoạn 1).
 */
public class InventoryDAO {

    // ================================================================
    // DTO / RECORD DEFINITIONS
    // ================================================================

    public static record StockRow(
            long productId,
            String name,
            long price,
            String unit,
            String type,
            String status,
            int qty,
            String updatedAt
    ) {
        public boolean isOutOfStock() {
            return qty <= 0;
        }
    }

    public static record AdjustmentHistoryRow(
            long id,
            long branchId,
            long productId,
            int deltaQty,
            String reason,
            long actorId,
            String actorName,
            String createdAt
    ) {}

    public static record MaterialStockRow(
            long materialId,
            String code,
            String name,
            String category,
            String unit,
            double minStock,
            long costPrice,
            String supplier,
            String status,
            double qty,
            String updatedAt
    ) {}

    public static record MaterialAdjustmentRow(
            long id,
            long branchId,
            long materialId,
            double deltaQty,
            String reason,
            Long costPrice,
            String supplier,
            String poNumber,
            String note,
            long actorId,
            String actorName,
            String createdAt
    ) {}

    public static record RecipeItemRow(
            long id,
            long productId,
            String productName,
            String productType,
            long materialId,
            String materialCode,
            String materialName,
            double quantityRequired,
            String unit,
            double lossRate,
            String note,
            boolean isActive
    ) {}

    public static record RecipeItemInput(
            long materialId,
            double dosage,
            double lossRate,
            String note,
            String unit
    ) {}

    // ================================================================
    // MÀN 1: TỒN KHO THÀNH PHẨM (M-P1 -> M-P4)
    // ================================================================

    /** Tồn kho hiện tại của một sản phẩm tại một chi nhánh. */
    public int findQty(long branchId, long productId) throws Exception {
        String sql = "SELECT qty FROM dbo.branch_inventory WHERE branch_id = ? AND product_id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("qty") : 0;
            }
        }
    }

    /** Toàn bộ tồn kho bắp nước của chi nhánh (M-P1). */
    public List<StockRow> findByBranch(long branchId) throws Exception {
        String sql = """
                SELECT p.id AS product_id, ISNULL(bi.qty, 0) AS qty, p.name, p.price, p.unit, p.type, p.status,
                       CONVERT(VARCHAR(19), bi.updated_at, 120) AS updated_at
                FROM dbo.product p
                LEFT JOIN dbo.branch_inventory bi ON bi.product_id = p.id AND bi.branch_id = ?
                WHERE p.status = 'ACTIVE'
                ORDER BY p.name
                """;
        List<StockRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StockRow(
                            rs.getLong("product_id"),
                            rs.getString("name"),
                            rs.getLong("price"),
                            rs.getString("unit"),
                            rs.getString("type"),
                            rs.getString("status"),
                            rs.getInt("qty"),
                            rs.getString("updated_at")
                    ));
                }
            }
        }
        return rows;
    }

    /** Nhập kho thành phẩm (M-P3): UPSERT tăng qty. */
    public void restock(Connection conn, long branchId, long productId, int deltaQty) throws SQLException {
        String merge = """
                UPDATE dbo.branch_inventory
                SET qty = qty + ?, updated_at = SYSUTCDATETIME()
                WHERE branch_id = ? AND product_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(merge)) {
            ps.setInt(1, deltaQty);
            ps.setLong(2, branchId);
            ps.setLong(3, productId);
            if (ps.executeUpdate() == 0) {
                String insert = """
                        INSERT INTO dbo.branch_inventory (branch_id, product_id, qty)
                        VALUES (?, ?, ?)
                        """;
                try (PreparedStatement ins = conn.prepareStatement(insert)) {
                    ins.setLong(1, branchId);
                    ins.setLong(2, productId);
                    ins.setInt(3, deltaQty);
                    ins.executeUpdate();
                }
            }
        }
    }

    /** Giảm kho có chốt chặn (M-P4): UPDATE qty = qty - n WHERE qty >= n. */
    public boolean tryDeduct(Connection conn, long branchId, long productId, int qty) throws SQLException {
        String sql = """
                UPDATE dbo.branch_inventory
                SET qty = qty - ?, updated_at = SYSUTCDATETIME()
                WHERE branch_id = ? AND product_id = ? AND qty >= ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, qty);
            ps.setLong(2, branchId);
            ps.setLong(3, productId);
            ps.setInt(4, qty);
            return ps.executeUpdate() == 1;
        }
    }

    /** Hoàn kho khi hủy đơn. */
    public void restore(Connection conn, long branchId, long productId, int qty) throws SQLException {
        restock(conn, branchId, productId, qty);
    }

    /** Ghi lịch sử biến động kho thành phẩm (M-P2, M-P3, M-P4). */
    public void insertAdjustment(Connection conn, long branchId, long productId,
                                 int deltaQty, String reason, long actorId) throws SQLException {
        String sql = """
                INSERT INTO dbo.inventory_adjustment (branch_id, product_id, delta_qty, reason, actor_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, productId);
            ps.setInt(3, deltaQty);
            ps.setString(4, reason);
            ps.setLong(5, actorId);
            ps.executeUpdate();
        }
    }

    /** Lấy lịch sử biến động của 1 sản phẩm tại chi nhánh (M-P2). */
    public List<AdjustmentHistoryRow> findAdjustments(long branchId, long productId) throws Exception {
        String sql = """
                SELECT ia.id, ia.branch_id, ia.product_id, ia.delta_qty, ia.reason, ia.actor_id,
                       u.full_name AS actor_name, CONVERT(VARCHAR(19), ia.created_at, 120) AS created_at
                FROM dbo.inventory_adjustment ia
                LEFT JOIN dbo.user_account u ON u.id = ia.actor_id
                WHERE ia.branch_id = ? AND ia.product_id = ?
                ORDER BY ia.created_at DESC
                """;
        List<AdjustmentHistoryRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new AdjustmentHistoryRow(
                            rs.getLong("id"),
                            rs.getLong("branch_id"),
                            rs.getLong("product_id"),
                            rs.getInt("delta_qty"),
                            rs.getString("reason"),
                            rs.getLong("actor_id"),
                            rs.getString("actor_name"),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return rows;
    }

    // ================================================================
    // MÀN 2: KHO NGUYÊN LIỆU THÔ (M-R1 -> M-R5)
    // ================================================================

    /** Lấy tồn kho nguyên liệu tại chi nhánh (M-R1). */
    public List<MaterialStockRow> findMaterialsByBranch(long branchId) throws Exception {
        String sql = """
                SELECT m.id AS material_id, m.code, m.name, m.category, m.unit,
                       ISNULL(m.min_stock, 0) AS min_stock, ISNULL(m.cost_price, 0) AS cost_price,
                       ISNULL(m.supplier, '') AS supplier, ISNULL(m.status, 'ACTIVE') AS status,
                       ISNULL(bmi.qty, 0) AS qty,
                       CONVERT(VARCHAR(19), bmi.updated_at, 120) AS updated_at
                FROM dbo.material m
                LEFT JOIN dbo.branch_material_inventory bmi ON bmi.material_id = m.id AND bmi.branch_id = ?
                ORDER BY m.code
                """;
        List<MaterialStockRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new MaterialStockRow(
                            rs.getLong("material_id"),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("category"),
                            rs.getString("unit"),
                            rs.getDouble("min_stock"),
                            rs.getLong("cost_price"),
                            rs.getString("supplier"),
                            rs.getString("status"),
                            rs.getDouble("qty"),
                            rs.getString("updated_at")
                    ));
                }
            }
        }
        return rows;
    }

    /** Lịch sử biến động nguyên liệu tại chi nhánh (M-R2). */
    public List<MaterialAdjustmentRow> findMaterialAdjustments(long branchId, long materialId) throws Exception {
        String sql = """
                SELECT ma.id, ma.branch_id, ma.material_id, ma.delta_qty, ma.reason,
                       ma.cost_price, ma.supplier, ma.po_number, ma.note, ma.actor_id,
                       u.full_name AS actor_name,
                       CONVERT(VARCHAR(19), ma.created_at, 120) AS created_at
                FROM dbo.material_adjustment ma
                LEFT JOIN dbo.user_account u ON u.id = ma.actor_id
                WHERE ma.branch_id = ? AND ma.material_id = ?
                ORDER BY ma.created_at DESC
                """;
        List<MaterialAdjustmentRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, materialId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long cp = rs.getLong("cost_price");
                    Long costPrice = rs.wasNull() ? null : cp;
                    rows.add(new MaterialAdjustmentRow(
                            rs.getLong("id"),
                            rs.getLong("branch_id"),
                            rs.getLong("material_id"),
                            rs.getDouble("delta_qty"),
                            rs.getString("reason"),
                            costPrice,
                            rs.getString("supplier"),
                            rs.getString("po_number"),
                            rs.getString("note"),
                            rs.getLong("actor_id"),
                            rs.getString("actor_name"),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return rows;
    }

    /** Nhập kho nguyên liệu (M-R3): UPSERT tăng qty + ghi nhật ký material_adjustment. */
    public void restockMaterial(Connection conn, long branchId, long materialId, double deltaQty,
                               String reason, Long costPrice, String supplier, String poNumber,
                               String note, long actorId) throws SQLException {
        String update = """
                UPDATE dbo.branch_material_inventory
                SET qty = qty + ?, updated_at = SYSUTCDATETIME()
                WHERE branch_id = ? AND material_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setDouble(1, deltaQty);
            ps.setLong(2, branchId);
            ps.setLong(3, materialId);
            if (ps.executeUpdate() == 0) {
                String insert = """
                        INSERT INTO dbo.branch_material_inventory (branch_id, material_id, qty, updated_at)
                        VALUES (?, ?, ?, SYSUTCDATETIME())
                        """;
                try (PreparedStatement ins = conn.prepareStatement(insert)) {
                    ins.setLong(1, branchId);
                    ins.setLong(2, materialId);
                    ins.setDouble(3, deltaQty);
                    ins.executeUpdate();
                }
            }
        }

        String insertAdj = """
                INSERT INTO dbo.material_adjustment (branch_id, material_id, delta_qty, reason, cost_price, supplier, po_number, note, actor_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, SYSUTCDATETIME())
                """;
        try (PreparedStatement ps = conn.prepareStatement(insertAdj)) {
            ps.setLong(1, branchId);
            ps.setLong(2, materialId);
            ps.setDouble(3, deltaQty);
            ps.setString(4, reason);
            if (costPrice != null) {
                ps.setLong(5, costPrice);
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.setString(6, supplier);
            ps.setString(7, poNumber);
            ps.setString(8, note);
            ps.setLong(9, actorId);
            ps.executeUpdate();
        }
    }

    /** Điều chỉnh tồn kho nguyên liệu (M-R5): Kiểm tra chống âm kho + ghi nhật ký. */
    public boolean adjustMaterial(Connection conn, long branchId, long materialId, double deltaQty,
                                 String reason, String note, long actorId) throws SQLException {
        if (deltaQty > 0) {
            String update = """
                    UPDATE dbo.branch_material_inventory
                    SET qty = qty + ?, updated_at = SYSUTCDATETIME()
                    WHERE branch_id = ? AND material_id = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setDouble(1, deltaQty);
                ps.setLong(2, branchId);
                ps.setLong(3, materialId);
                if (ps.executeUpdate() == 0) {
                    String insert = """
                            INSERT INTO dbo.branch_material_inventory (branch_id, material_id, qty, updated_at)
                            VALUES (?, ?, ?, SYSUTCDATETIME())
                            """;
                    try (PreparedStatement ins = conn.prepareStatement(insert)) {
                        ins.setLong(1, branchId);
                        ins.setLong(2, materialId);
                        ins.setDouble(3, deltaQty);
                        ins.executeUpdate();
                    }
                }
            }
        } else {
            String deduct = """
                    UPDATE dbo.branch_material_inventory
                    SET qty = qty + ?, updated_at = SYSUTCDATETIME()
                    WHERE branch_id = ? AND material_id = ? AND qty >= ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(deduct)) {
                ps.setDouble(1, deltaQty);
                ps.setLong(2, branchId);
                ps.setLong(3, materialId);
                ps.setDouble(4, -deltaQty);
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
        }

        String insertAdj = """
                INSERT INTO dbo.material_adjustment (branch_id, material_id, delta_qty, reason, note, actor_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, SYSUTCDATETIME())
                """;
        try (PreparedStatement ps = conn.prepareStatement(insertAdj)) {
            ps.setLong(1, branchId);
            ps.setLong(2, materialId);
            ps.setDouble(3, deltaQty);
            ps.setString(4, reason);
            ps.setString(5, note);
            ps.setLong(6, actorId);
            ps.executeUpdate();
        }
        return true;
    }

    // ================================================================
    // MÀN 3: CÔNG THỨC CHẾ BIẾN & XUẤT CHẾ BIẾN (M-X1 -> M-X3, M-R4)
    // ================================================================

    /** Lấy toàn bộ công thức định mức của tất cả sản phẩm bán quầy. */
    public List<RecipeItemRow> findAllRecipes() throws Exception {
        String sql = """
                SELECT r.id, r.product_id, p.name AS product_name, ISNULL(p.type, 'POPCORN') AS product_type,
                       r.material_id, m.code AS material_code, m.name AS material_name,
                       ISNULL(r.quantity_required, ISNULL(r.dosage, 0)) AS quantity_required,
                       ISNULL(r.unit, m.unit) AS unit,
                       ISNULL(r.loss_rate, 0) AS loss_rate,
                       ISNULL(r.note, '') AS note,
                       ISNULL(r.is_active, 1) AS is_active
                FROM dbo.recipe_item r
                JOIN dbo.product p ON p.id = r.product_id
                JOIN dbo.material m ON m.id = r.material_id
                ORDER BY p.name, m.code
                """;
        List<RecipeItemRow> list = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new RecipeItemRow(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getString("product_type"),
                        rs.getLong("material_id"),
                        rs.getString("material_code"),
                        rs.getString("material_name"),
                        rs.getDouble("quantity_required"),
                        rs.getString("unit"),
                        rs.getDouble("loss_rate"),
                        rs.getString("note"),
                        rs.getBoolean("is_active")
                ));
            }
        }
        return list;
    }

    /** Lấy các dòng nguyên liệu trong công thức của 1 sản phẩm cụ thể. */
    public List<RecipeItemRow> findRecipesByProduct(Connection conn, long productId) throws SQLException {
        String sql = """
                SELECT r.id, r.product_id, p.name AS product_name, ISNULL(p.type, 'POPCORN') AS product_type,
                       r.material_id, m.code AS material_code, m.name AS material_name,
                       ISNULL(r.quantity_required, ISNULL(r.dosage, 0)) AS quantity_required,
                       ISNULL(r.unit, m.unit) AS unit,
                       ISNULL(r.loss_rate, 0) AS loss_rate,
                       ISNULL(r.note, '') AS note,
                       ISNULL(r.is_active, 1) AS is_active
                FROM dbo.recipe_item r
                JOIN dbo.product p ON p.id = r.product_id
                JOIN dbo.material m ON m.id = r.material_id
                WHERE r.product_id = ? AND ISNULL(r.is_active, 1) = 1
                ORDER BY m.code
                """;
        List<RecipeItemRow> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new RecipeItemRow(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            rs.getString("product_name"),
                            rs.getString("product_type"),
                            rs.getLong("material_id"),
                            rs.getString("material_code"),
                            rs.getString("material_name"),
                            rs.getDouble("quantity_required"),
                            rs.getString("unit"),
                            rs.getDouble("loss_rate"),
                            rs.getString("note"),
                            rs.getBoolean("is_active")
                    ));
                }
            }
        }
        return list;
    }

    /** Lưu (thêm/sửa) toàn bộ nguyên liệu định mức cho 1 sản phẩm (M-X2). */
    public void saveRecipe(long productId, List<RecipeItemInput> items) throws Exception {
        String delSql = "DELETE FROM dbo.recipe_item WHERE product_id = ?";
        String insSql = """
                INSERT INTO dbo.recipe_item (product_id, material_id, dosage, loss_rate, note, quantity_required, unit, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """;
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(delSql)) {
                    ps.setLong(1, productId);
                    ps.executeUpdate();
                }
                if (items != null && !items.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                        for (RecipeItemInput item : items) {
                            ps.setLong(1, productId);
                            ps.setLong(2, item.materialId());
                            ps.setDouble(3, item.dosage());
                            ps.setDouble(4, item.lossRate());
                            ps.setString(5, item.note());
                            ps.setDouble(6, item.dosage());
                            ps.setString(7, item.unit());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Thực thi lệnh Chế biến bắp nước (M-R4):
     * 1. Trừ nguyên liệu theo định mức (chống âm kho).
     * 2. Ghi lịch sử xuất nguyên liệu (XUAT_CHE_BIEN).
     * 3. Tăng kho thành phẩm + ghi lịch sử nhập thành phẩm (NHAP_TU_CHE_BIEN).
     */
    public void produceProduct(Connection conn, long branchId, long productId,
                               int productQty, long actorId, String note) throws Exception {
        List<RecipeItemRow> recipeItems = findRecipesByProduct(conn, productId);
        if (recipeItems.isEmpty()) {
            throw new IllegalStateException("Sản phẩm chưa được cấu hình công thức chế biến.");
        }

        // BƯỚC 1: Trừ từng nguyên liệu theo định mức
        for (RecipeItemRow item : recipeItems) {
            double totalNeeded = item.quantityRequired() * productQty;

            String deductSql = """
                    UPDATE dbo.branch_material_inventory
                    SET qty = qty - ?, updated_at = SYSUTCDATETIME()
                    WHERE branch_id = ? AND material_id = ? AND qty >= ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(deductSql)) {
                ps.setDouble(1, totalNeeded);
                ps.setLong(2, branchId);
                ps.setLong(3, item.materialId());
                ps.setDouble(4, totalNeeded);
                int affected = ps.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("Không đủ tồn kho nguyên liệu: "
                            + item.materialName() + " (Cần: " + totalNeeded + " " + item.unit() + ")");
                }
            }

            // Ghi vết lịch sử xuất nguyên liệu
            String adjSql = """
                    INSERT INTO dbo.material_adjustment (branch_id, material_id, delta_qty, reason, note, actor_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, SYSUTCDATETIME())
                    """;
            try (PreparedStatement ps = conn.prepareStatement(adjSql)) {
                ps.setLong(1, branchId);
                ps.setLong(2, item.materialId());
                ps.setDouble(3, -totalNeeded);
                ps.setString(4, "XUAT_CHE_BIEN");
                ps.setString(5, (note != null && !note.isBlank() ? note : "Chế biến " + productQty + " " + item.productName()));
                ps.setLong(6, actorId);
                ps.executeUpdate();
            }
        }

        // BƯỚC 2: Tăng tồn kho sản phẩm thành phẩm
        restock(conn, branchId, productId, productQty);

        // BƯỚC 3: Ghi lịch sử nhập thành phẩm
        insertAdjustment(conn, branchId, productId, productQty, "NHAP_TU_CHE_BIEN", actorId);
    }
}
