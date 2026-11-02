package com.cinema.dao.concession;

import com.cinema.service.concession.ConcessionService;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Data access for branch_inventory + inventory_adjustment (Req 19.4-19.7). */
public class InventoryDAO {

    /** Tồn kho hiện tại của một sản phẩm tại một chi nhánh (0 nếu chưa có dòng). */
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

    /** Toàn bộ tồn kho của chi nhánh kèm tên sản phẩm (màn hình quản lý kho). */
    public List<StockRow> findByBranch(long branchId) throws Exception {
        String sql = """
            SELECT bi.product_id, bi.qty, p.name, p.price, p.unit, p.type, p.status
            FROM dbo.branch_inventory bi
            JOIN dbo.product p ON p.id = bi.product_id
            WHERE bi.branch_id = ? ORDER BY p.name
            """;
        List<StockRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StockRow(rs.getLong("product_id"), rs.getString("name"),
                            rs.getLong("price"), rs.getString("unit"), rs.getString("type"),
                            rs.getString("status"), rs.getInt("qty")));
                }
            }
        }
        return rows;
    }

    /** Nhập kho (Req 19.4): UPSERT tăng qty trong connection của caller. */
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

    /**
     * Guarded stock decrement (Req 19.6, 20.4, 21.8 — design.md ConcessionService):
     * UPDATE qty = qty - n WHERE qty >= n. Hai đơn tranh sản phẩm cuối tồn kho →
     * đúng một affectedRows == 1; CK_inventory_qty (qty >= 0) là chốt chặn cuối.
     *
     * @return false nếu không đủ tồn kho
     */
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

    /** Hoàn kho khi hủy đơn (Req 20.5, 21.6). */
    public void restore(Connection conn, long branchId, long productId, int qty) throws SQLException {
        restock(conn, branchId, productId, qty);
    }

    /** Ghi bản ghi kiểm kê/nhập kho kèm lý do (Req 19.4, 19.5). */
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

    /** Lịch sử biến động tồn kho của 1 sản phẩm tại 1 chi nhánh (M-P2: màn hình kho). */
    public List<AdjustmentRow> findAdjustmentHistory(long branchId, long productId) throws Exception {
        String sql = """
            SELECT a.id, a.branch_id, a.product_id, a.delta_qty, a.reason, a.actor_id,
                   a.created_at, u.full_name AS actor_name
            FROM dbo.inventory_adjustment a
            LEFT JOIN dbo.user_account u ON u.id = a.actor_id
            WHERE a.branch_id = ? AND a.product_id = ?
            ORDER BY a.created_at DESC, a.id DESC
            """;
        List<AdjustmentRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new AdjustmentRow(
                            rs.getLong("id"),
                            rs.getLong("branch_id"),
                            rs.getLong("product_id"),
                            rs.getInt("delta_qty"),
                            rs.getString("reason"),
                            rs.getLong("actor_id"),
                            rs.getString("actor_name"),
                            rs.getTimestamp("created_at") == null ? null
                                    : rs.getTimestamp("created_at").toLocalDateTime()
                    ));
                }
            }
        }
        return rows;
    }

    /** Một dòng tồn kho hiển thị trên màn hình quản lý. */
    public record StockRow(long productId, String name, long price, String unit,
                           String type, String productStatus, int qty) {
        /** Req 19.6 — qty == 0 → hết hàng tại chi nhánh, chặn bán cả 2 kênh. */
        public boolean isOutOfStock() { return qty <= 0; }
    }

    /** Một dòng lịch sử biến động tồn kho (M-P2). */
    public record AdjustmentRow(long id, long branchId, long productId, int deltaQty,
                                 String reason, long actorId, String actorName,
                                 java.time.LocalDateTime createdAt) {
    }

    // ================================================================
    // GIAI ĐOẠN 2: KHO NGUYÊN LIỆU THÔ (RAW MATERIAL)
    // ================================================================
    public record MaterialStockRow(
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
            java.time.LocalDateTime updatedAt) {
    }

    public record MaterialAdjustmentRow(
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
            java.time.LocalDateTime createdAt) {
    }

    public List<MaterialStockRow> findMaterialsByBranch(long branchId) throws Exception {
        String sql = """
            SELECT m.id AS material_id, m.code, m.name, m.category, m.unit,
                   ISNULL(m.min_stock, 0) AS min_stock, ISNULL(m.cost_price, 0) AS cost_price,
                   ISNULL(m.supplier, '') AS supplier, ISNULL(m.status, 'ACTIVE') AS status,
                   ISNULL(bmi.qty, 0) AS qty,
                   bmi.updated_at
            FROM dbo.material m
            LEFT JOIN dbo.branch_material_inventory bmi ON bmi.material_id = m.id AND bmi.branch_id = ?
            WHERE ISNULL(m.status, 'ACTIVE') = 'ACTIVE'
            ORDER BY m.code
            """;
        List<MaterialStockRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("updated_at");
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
                            ts == null ? null : ts.toLocalDateTime()));
                }
            }
        }
        return rows;
    }

    public List<MaterialAdjustmentRow> findMaterialAdjustments(long branchId, long materialId) throws Exception {
        String sql = """
            SELECT ma.id, ma.branch_id, ma.material_id, ma.delta_qty, ma.reason,
                   ma.cost_price, ma.supplier, ma.po_number, ma.note, ma.actor_id,
                   u.full_name AS actor_name,
                   ma.created_at
            FROM dbo.material_adjustment ma
            LEFT JOIN dbo.user_account u ON u.id = ma.actor_id
            WHERE ma.branch_id = ? AND ma.material_id = ?
            ORDER BY ma.created_at DESC, ma.id DESC
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
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
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
                            ts == null ? null : ts.toLocalDateTime()));
                }
            }
        }
        return rows;
    }

    /** Nhập kho nguyên liệu kèm metadata (M-R3) — tăng tồn + ghi adjustment. */
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
            if (costPrice != null) ps.setLong(5, costPrice);
            else ps.setNull(5, java.sql.Types.BIGINT);
            ps.setString(6, supplier);
            ps.setString(7, poNumber);
            ps.setString(8, note);
            ps.setLong(9, actorId);
            ps.executeUpdate();
        }
    }

    /** Điều chỉnh kho nguyên liệu (M-R5) — âm/dương, không cho kho âm. */
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
            // Không cho âm kho: qty + deltaQty >= 0 (deltaQty < 0 => qty >= -deltaQty)
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
    // GIAI ĐOẠN 3: CÔNG THỨC (RECIPE) & XUẤT CHẾ BIẾN (PRODUCTION)
    // ================================================================
    public record RecipeItemRow(
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
            boolean isActive) {
    }

    public record RecipeItemInput(
            long materialId,
            double dosage,
            double lossRate,
            String note,
            String unit) {
    }

    /** Tất cả công thức định mức (gộp tất cả sản phẩm bán quầy). */
    public List<RecipeItemRow> findAllRecipes() throws Exception {
        String sql = """
            SELECT r.id, r.product_id, p.name AS product_name, ISNULL(p.type, 'POPCORN') AS product_type,
                   r.material_id, m.code AS material_code, m.name AS material_name,
                   ISNULL(r.quantity_required, ISNULL(r.dosage, 0)) AS quantity_required,
                   ISNULL(r.unit, m.unit) AS unit,
                   ISNULL(r.loss_rate, 0) AS loss_rate,
                   ISNULL(r.note, '') AS note,
                   CAST(ISNULL(r.is_active, 1) AS BIT) AS is_active
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
                        rs.getBoolean("is_active")));
            }
        }
        return list;
    }

    /** Recipe của 1 sản phẩm cụ thể (chỉ active). */
    public List<RecipeItemRow> findRecipesByProduct(Connection conn, long productId) throws SQLException {
        String sql = """
            SELECT r.id, r.product_id, p.name AS product_name, ISNULL(p.type, 'POPCORN') AS product_type,
                   r.material_id, m.code AS material_code, m.name AS material_name,
                   ISNULL(r.quantity_required, ISNULL(r.dosage, 0)) AS quantity_required,
                   ISNULL(r.unit, m.unit) AS unit,
                   ISNULL(r.loss_rate, 0) AS loss_rate,
                   ISNULL(r.note, '') AS note,
                   CAST(ISNULL(r.is_active, 1) AS BIT) AS is_active
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
                            rs.getBoolean("is_active")));
                }
            }
        }
        return list;
    }

    /** Lưu toàn bộ recipe cho 1 sản phẩm (M-X2): xóa cũ + insert mới trong transaction. */
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
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Thực thi chế biến (M-R4):
     * 1. Trừ kho nguyên liệu theo công thức × số lượng sản phẩm (chống âm kho).
     * 2. Ghi lịch sử xuất nguyên liệu (XUAT_CHE_BIEN).
     * 3. Tăng kho thành phẩm + ghi lịch sử nhập thành phẩm (NHAP_TU_CHE_BIEN).
     */
    public void produceProduct(Connection conn, long branchId, long productId,
                               int productQty, long actorId, String note) throws Exception {
        List<RecipeItemRow> recipeItems = findRecipesByProduct(conn, productId);
        if (recipeItems.isEmpty()) {
            throw new IllegalStateException("Sản phẩm chưa được cấu hình công thức chế biến.");
        }

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

            String adjSql = """
                INSERT INTO dbo.material_adjustment (branch_id, material_id, delta_qty, reason, note, actor_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, SYSUTCDATETIME())
                """;
            try (PreparedStatement ps = conn.prepareStatement(adjSql)) {
                ps.setLong(1, branchId);
                ps.setLong(2, item.materialId());
                ps.setDouble(3, -totalNeeded);
                ps.setString(4, "XUAT_CHE_BIEN");
                ps.setString(5,
                        (note != null && !note.isBlank() ? note : "Chế biến " + productQty + " " + item.productName()));
                ps.setLong(6, actorId);
                ps.executeUpdate();
            }
        }

        // Tăng tồn kho thành phẩm (tái sử dụng restock product)
        restock(conn, branchId, productId, productQty);
        insertAdjustment(conn, branchId, productId, productQty, "NHAP_TU_CHE_BIEN", actorId);
    }
}
