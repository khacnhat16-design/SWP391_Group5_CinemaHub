package com.cinema.dao.inventory;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access cho tồn kho thành phẩm, nguyên liệu thô và công thức chế biến
 * (Giai đoạn 1).
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
            int minStock,
            String expiryDate,
            String batchNo,
            Integer daysUntilExpiry,
            String updatedAt) {
        public StockRow(long productId, String name, long price, String unit, String type, String status, int qty, String updatedAt) {
            this(productId, name, price, unit, type, status, qty, 10, null, null, null, updatedAt);
        }
        public boolean isOutOfStock() {
            return qty <= 0;
        }
        public boolean isLowStock() {
            return qty > 0 && qty <= (minStock > 0 ? minStock : 10);
        }
        public boolean isExpired() {
            return daysUntilExpiry != null && daysUntilExpiry < 0;
        }
        public boolean isExpiringSoon(int warningDays) {
            return daysUntilExpiry != null && daysUntilExpiry >= 0 && daysUntilExpiry <= warningDays;
        }
    }

    public static record AdjustmentHistoryRow(
            long id,
            long branchId,
            long productId,
            int deltaQty,
            String reason,
            String expiryDate,
            String batchNo,
            long actorId,
            String actorName,
            String createdAt) {
        public AdjustmentHistoryRow(long id, long branchId, long productId, int deltaQty, String reason, long actorId, String actorName, String createdAt) {
            this(id, branchId, productId, deltaQty, reason, null, null, actorId, actorName, createdAt);
        }
    }

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
            String expiryDate,
            String batchNo,
            Integer daysUntilExpiry,
            String updatedAt) {
        public MaterialStockRow(long materialId, String code, String name, String category, String unit, double minStock, long costPrice, String supplier, String status, double qty, String updatedAt) {
            this(materialId, code, name, category, unit, minStock, costPrice, supplier, status, qty, null, null, null, updatedAt);
        }
        public boolean isOutOfStock() {
            return qty <= 0;
        }
        public boolean isLowStock() {
            return qty > 0 && qty <= minStock;
        }
        public boolean isExpired() {
            return daysUntilExpiry != null && daysUntilExpiry < 0;
        }
        public boolean isExpiringSoon(int warningDays) {
            return daysUntilExpiry != null && daysUntilExpiry >= 0 && daysUntilExpiry <= warningDays;
        }
    }

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
            String expiryDate,
            String batchNo,
            long actorId,
            String actorName,
            String createdAt) {
        public MaterialAdjustmentRow(long id, long branchId, long materialId, double deltaQty, String reason, Long costPrice, String supplier, String poNumber, String note, long actorId, String actorName, String createdAt) {
            this(id, branchId, materialId, deltaQty, reason, costPrice, supplier, poNumber, note, null, null, actorId, actorName, createdAt);
        }
    }

    public static record InventoryAlertItem(
            String alertType,
            String itemType,
            long itemId,
            String itemName,
            String unit,
            double currentQty,
            double threshold,
            String expiryDate,
            String batchNo,
            Integer daysUntilExpiry,
            String message) {
    }

    public static record RecipeItemRow(
            long id,
            long productId,
            String productName,
            String productType,
            long productPrice,
            long materialId,
            String materialCode,
            String materialName,
            String materialUnit,
            long materialCostPrice,
            double dosage,
            double lossRate,
            double effectiveDosage,
            String note,
            boolean isActive) {
        public static double calcEffectiveDosage(double dosage, double lossRate, String materialUnit) {
            double rate = lossRate > 0 ? (lossRate > 1.0 ? lossRate / 100.0 : lossRate) : 0.0;
            double eff = dosage * (1.0 + rate);
            String u = materialUnit != null ? materialUnit.trim().toLowerCase() : "";
            if (u.equals("cái") || u.equals("cai") || u.equals("ly") || u.equals("hộp") || u.equals("hop")
                    || u.equals("ống") || u.equals("ong")) {
                return Math.max(1.0, Math.ceil(dosage));
            }
            return Math.round(eff * 10000.0) / 10000.0;
        }

        public double estimatedCost() {
            return effectiveDosage * (materialCostPrice > 0 ? materialCostPrice : 0);
        }

        public String name() {
            return materialName;
        }

        public String code() {
            return materialCode;
        }

        public String unit() {
            return materialUnit;
        }

        public long costPrice() {
            return materialCostPrice;
        }
    }

    public static record RecipeProductSummary(
            long productId,
            String name,
            String type,
            long price,
            String unit,
            String status,
            int itemCount,
            double totalEstimatedCost,
            boolean hasRecipe,
            String cookingTime,
            List<RecipeItemRow> items) {
        public static String resolveCookingTime(String type) {
            if ("POPCORN".equalsIgnoreCase(type))
                return "2.5 phút/mẻ";
            if ("DRINK".equalsIgnoreCase(type))
                return "30 giây/ly";
            if ("COMBO".equalsIgnoreCase(type))
                return "Đóng gói combo (1 phút)";
            return "2.0 phút/phần";
        }

        public long id() {
            return productId;
        }

        public String productName() {
            return name;
        }

        public String productType() {
            return type;
        }

        public long productPrice() {
            return price;
        }

        public String productUnit() {
            return unit;
        }

        public String productStatus() {
            return status;
        }

        public int materialCount() {
            return itemCount;
        }
    }

    public static record RecipeItemInput(
            long materialId,
            double dosage,
            double lossRate,
            String note) {
    }

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
                       ISNULL(bi.min_stock, 10) AS min_stock,
                       CONVERT(VARCHAR(10), bi.expiry_date, 120) AS expiry_date,
                       bi.batch_no,
                       DATEDIFF(day, CAST(GETDATE() AS DATE), bi.expiry_date) AS days_until_expiry,
                       CONVERT(VARCHAR(19), bi.updated_at, 120) AS updated_at
                FROM dbo.product p
                LEFT JOIN dbo.branch_inventory bi ON bi.product_id = p.id AND bi.branch_id = ?
                WHERE p.status = 'ACTIVE'
                ORDER BY bi.updated_at DESC, p.id ASC
                """;
        List<StockRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int days = rs.getInt("days_until_expiry");
                    Integer daysUntil = rs.wasNull() ? null : days;
                    rows.add(new StockRow(
                            rs.getLong("product_id"),
                            rs.getString("name"),
                            rs.getLong("price"),
                            rs.getString("unit"),
                            rs.getString("type"),
                            rs.getString("status"),
                            rs.getInt("qty"),
                            rs.getInt("min_stock"),
                            rs.getString("expiry_date"),
                            rs.getString("batch_no"),
                            daysUntil,
                            rs.getString("updated_at")));
                }
            }
        }
        return rows;
    }

    /** Nhập kho thành phẩm (M-P3): UPSERT tăng qty kèm ngày hết hạn và mã lô. */
    public void restock(Connection conn, long branchId, long productId, int deltaQty, String expiryDate, String batchNo) throws SQLException {
        String merge = """
                UPDATE dbo.branch_inventory
                SET qty = qty + ?,
                    expiry_date = CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE expiry_date END,
                    batch_no = CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN ? ELSE batch_no END,
                    updated_at = SYSUTCDATETIME()
                WHERE branch_id = ? AND product_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(merge)) {
            ps.setInt(1, deltaQty);
            ps.setString(2, expiryDate);
            ps.setString(3, expiryDate);
            ps.setString(4, expiryDate);
            ps.setString(5, batchNo);
            ps.setString(6, batchNo);
            ps.setString(7, batchNo);
            ps.setLong(8, branchId);
            ps.setLong(9, productId);
            if (ps.executeUpdate() == 0) {
                String insert = """
                        INSERT INTO dbo.branch_inventory (branch_id, product_id, qty, expiry_date, batch_no, updated_at)
                        VALUES (?, ?, ?, CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE NULL END, ?, SYSUTCDATETIME())
                        """;
                try (PreparedStatement ins = conn.prepareStatement(insert)) {
                    ins.setLong(1, branchId);
                    ins.setLong(2, productId);
                    ins.setInt(3, deltaQty);
                    ins.setString(4, expiryDate);
                    ins.setString(5, expiryDate);
                    ins.setString(6, expiryDate);
                    ins.setString(7, batchNo);
                    ins.executeUpdate();
                }
            }
        }
    }

    public void restock(Connection conn, long branchId, long productId, int deltaQty) throws SQLException {
        restock(conn, branchId, productId, deltaQty, null, null);
    }

    /** Cập nhật định mức tồn kho tối thiểu cho sản phẩm tại chi nhánh (Feature 13: Configure low-stock thresholds). */
    public boolean updateProductMinStock(long branchId, long productId, int minStock) throws SQLException {
        String update = """
                UPDATE dbo.branch_inventory
                SET min_stock = ?, updated_at = SYSUTCDATETIME()
                WHERE branch_id = ? AND product_id = ?
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setInt(1, minStock);
            ps.setLong(2, branchId);
            ps.setLong(3, productId);
            if (ps.executeUpdate() > 0) {
                return true;
            }
            String insert = """
                    INSERT INTO dbo.branch_inventory (branch_id, product_id, qty, min_stock, updated_at)
                    VALUES (?, ?, 0, ?, SYSUTCDATETIME())
                    """;
            try (PreparedStatement ins = conn.prepareStatement(insert)) {
                ins.setLong(1, branchId);
                ins.setLong(2, productId);
                ins.setInt(3, minStock);
                return ins.executeUpdate() > 0;
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
        restock(conn, branchId, productId, qty, null, null);
    }

    /** Ghi lịch sử biến động kho thành phẩm (M-P2, M-P3, M-P4). */
    public void insertAdjustment(Connection conn, long branchId, long productId,
            int deltaQty, String reason, String expiryDate, String batchNo, long actorId) throws SQLException {
        String sql = """
                INSERT INTO dbo.inventory_adjustment (branch_id, product_id, delta_qty, reason, expiry_date, batch_no, actor_id, created_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE NULL END, ?, ?, SYSUTCDATETIME())
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, productId);
            ps.setInt(3, deltaQty);
            ps.setString(4, reason);
            ps.setString(5, expiryDate);
            ps.setString(6, expiryDate);
            ps.setString(7, expiryDate);
            ps.setString(8, batchNo);
            ps.setLong(9, actorId);
            ps.executeUpdate();
        }
    }

    public void insertAdjustment(Connection conn, long branchId, long productId,
            int deltaQty, String reason, long actorId) throws SQLException {
        insertAdjustment(conn, branchId, productId, deltaQty, reason, null, null, actorId);
    }

    public void insertAdjustment(Connection conn, long branchId, long productId,
            int deltaQty, String reason, String note, long actorId) throws SQLException {
        String fullReason = reason + (note != null && !note.isBlank() ? " - " + note.trim() : "");
        insertAdjustment(conn, branchId, productId, deltaQty, fullReason, null, null, actorId);
    }

    /** Lấy lịch sử biến động của 1 sản phẩm tại chi nhánh (M-P2). */
    public List<AdjustmentHistoryRow> findAdjustments(long branchId, long productId) throws Exception {
        String sql = """
                SELECT ia.id, ia.branch_id, ia.product_id, ia.delta_qty, ia.reason,
                       CONVERT(VARCHAR(10), ia.expiry_date, 120) AS expiry_date, ia.batch_no,
                       ia.actor_id, u.full_name AS actor_name, CONVERT(VARCHAR(19), ia.created_at, 120) AS created_at
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
                            rs.getString("created_at")));
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
                       CONVERT(VARCHAR(10), bmi.expiry_date, 120) AS expiry_date,
                       bmi.batch_no,
                       DATEDIFF(day, CAST(GETDATE() AS DATE), bmi.expiry_date) AS days_until_expiry,
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
                    int days = rs.getInt("days_until_expiry");
                    Integer daysUntil = rs.wasNull() ? null : days;
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
                            rs.getString("expiry_date"),
                            rs.getString("batch_no"),
                            daysUntil,
                            rs.getString("updated_at")));
                }
            }
        }
        return rows;
    }

    /** Lịch sử biến động nguyên liệu tại chi nhánh (M-R2). */
    public List<MaterialAdjustmentRow> findMaterialAdjustments(long branchId, long materialId) throws Exception {
        String sql = """
                SELECT ma.id, ma.branch_id, ma.material_id, ma.delta_qty, ma.reason,
                       ma.cost_price, ma.supplier, ma.po_number, ma.note,
                       CONVERT(VARCHAR(10), ma.expiry_date, 120) AS expiry_date, ma.batch_no,
                       ma.actor_id, u.full_name AS actor_name,
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
                            rs.getString("expiry_date"),
                            rs.getString("batch_no"),
                            rs.getLong("actor_id"),
                            rs.getString("actor_name"),
                            rs.getString("created_at")));
                }
            }
        }
        return rows;
    }

    /**
     * Nhập kho nguyên liệu (M-R3): UPSERT tăng qty kèm ngày hết hạn và mã lô + ghi nhật ký
     * material_adjustment.
     */
    public void restockMaterial(Connection conn, long branchId, long materialId, double deltaQty,
            String reason, Long costPrice, String supplier, String poNumber,
            String note, String expiryDate, String batchNo, long actorId) throws SQLException {
        String update = """
                UPDATE dbo.branch_material_inventory
                SET qty = qty + ?,
                    expiry_date = CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE expiry_date END,
                    batch_no = CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN ? ELSE batch_no END,
                    updated_at = SYSUTCDATETIME()
                WHERE branch_id = ? AND material_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setDouble(1, deltaQty);
            ps.setString(2, expiryDate);
            ps.setString(3, expiryDate);
            ps.setString(4, expiryDate);
            ps.setString(5, batchNo);
            ps.setString(6, batchNo);
            ps.setString(7, batchNo);
            ps.setLong(8, branchId);
            ps.setLong(9, materialId);
            if (ps.executeUpdate() == 0) {
                String insert = """
                        INSERT INTO dbo.branch_material_inventory (branch_id, material_id, qty, expiry_date, batch_no, updated_at)
                        VALUES (?, ?, ?, CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE NULL END, ?, SYSUTCDATETIME())
                        """;
                try (PreparedStatement ins = conn.prepareStatement(insert)) {
                    ins.setLong(1, branchId);
                    ins.setLong(2, materialId);
                    ins.setDouble(3, deltaQty);
                    ins.setString(4, expiryDate);
                    ins.setString(5, expiryDate);
                    ins.setString(6, expiryDate);
                    ins.setString(7, batchNo);
                    ins.executeUpdate();
                }
            }
        }

        String insertAdj = """
                INSERT INTO dbo.material_adjustment (branch_id, material_id, delta_qty, reason, cost_price, supplier, po_number, note, expiry_date, batch_no, actor_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE NULL END, ?, ?, SYSUTCDATETIME())
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
            ps.setString(9, expiryDate);
            ps.setString(10, expiryDate);
            ps.setString(11, expiryDate);
            ps.setString(12, batchNo);
            ps.setLong(13, actorId);
            ps.executeUpdate();
        }
    }

    public void restockMaterial(Connection conn, long branchId, long materialId, double deltaQty,
            String reason, Long costPrice, String supplier, String poNumber,
            String note, long actorId) throws SQLException {
        restockMaterial(conn, branchId, materialId, deltaQty, reason, costPrice, supplier, poNumber, note, null, null, actorId);
    }

    /**
     * Điều chỉnh tồn kho nguyên liệu (M-R5): Kiểm tra chống âm kho + ghi nhật ký.
     */
    public boolean adjustMaterial(Connection conn, long branchId, long materialId, double deltaQty,
            String reason, String note, String expiryDate, String batchNo, long actorId) throws SQLException {
        if (deltaQty > 0) {
            String update = """
                    UPDATE dbo.branch_material_inventory
                    SET qty = qty + ?,
                        expiry_date = CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE expiry_date END,
                        batch_no = CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN ? ELSE batch_no END,
                        updated_at = SYSUTCDATETIME()
                    WHERE branch_id = ? AND material_id = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setDouble(1, deltaQty);
                ps.setString(2, expiryDate);
                ps.setString(3, expiryDate);
                ps.setString(4, expiryDate);
                ps.setString(5, batchNo);
                ps.setString(6, batchNo);
                ps.setString(7, batchNo);
                ps.setLong(8, branchId);
                ps.setLong(9, materialId);
                if (ps.executeUpdate() == 0) {
                    String insert = """
                            INSERT INTO dbo.branch_material_inventory (branch_id, material_id, qty, expiry_date, batch_no, updated_at)
                            VALUES (?, ?, ?, CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE NULL END, ?, SYSUTCDATETIME())
                            """;
                    try (PreparedStatement ins = conn.prepareStatement(insert)) {
                        ins.setLong(1, branchId);
                        ins.setLong(2, materialId);
                        ins.setDouble(3, deltaQty);
                        ins.setString(4, expiryDate);
                        ins.setString(5, expiryDate);
                        ins.setString(6, expiryDate);
                        ins.setString(7, batchNo);
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
                INSERT INTO dbo.material_adjustment (branch_id, material_id, delta_qty, reason, note, expiry_date, batch_no, actor_id, created_at)
                VALUES (?, ?, ?, ?, ?, CASE WHEN ? IS NOT NULL AND LEN(?) > 0 THEN CAST(? AS DATE) ELSE NULL END, ?, ?, SYSUTCDATETIME())
                """;
        try (PreparedStatement ps = conn.prepareStatement(insertAdj)) {
            ps.setLong(1, branchId);
            ps.setLong(2, materialId);
            ps.setDouble(3, deltaQty);
            ps.setString(4, reason);
            ps.setString(5, note);
            ps.setString(6, expiryDate);
            ps.setString(7, expiryDate);
            ps.setString(8, expiryDate);
            ps.setString(9, batchNo);
            ps.setLong(10, actorId);
            ps.executeUpdate();
        }
        return true;
    }

    public boolean adjustMaterial(Connection conn, long branchId, long materialId, double deltaQty,
            String reason, String note, long actorId) throws SQLException {
        return adjustMaterial(conn, branchId, materialId, deltaQty, reason, note, null, null, actorId);
    }

    /**
     * Cập nhật định mức tồn tối thiểu cho nguyên liệu (Dòng 78: Configure low-stock
     * thresholds).
     */
    public boolean updateMaterialMinStock(long materialId, double minStock) throws Exception {
        String sql = "UPDATE dbo.material SET min_stock = ? WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, minStock);
            ps.setLong(2, materialId);
            return ps.executeUpdate() > 0;
        }
    }

    // ================================================================
    // GIAI ĐOẠN 3: CÔNG THỨC & SẢN XUẤT (M-X1, M-X2, M-R4)
    // ================================================================

    /** Lấy công thức chi tiết của 1 sản phẩm. */
    public List<RecipeItemRow> findRecipesByProduct(long productId) throws Exception {
        String sql = """
                SELECT r.id, r.product_id, p.name AS product_name, p.type AS product_type, p.price AS product_price,
                       r.material_id, m.code AS material_code, m.name AS material_name, m.unit AS material_unit,
                       ISNULL(m.cost_price, 0) AS material_cost_price,
                       CAST(r.dosage AS FLOAT) AS dosage,
                       CAST(ISNULL(r.loss_rate, 0) AS FLOAT) AS loss_rate,
                       r.note, ISNULL(r.is_active, 1) AS is_active
                FROM dbo.recipe_item r
                JOIN dbo.product p ON p.id = r.product_id
                JOIN dbo.material m ON m.id = r.material_id
                WHERE r.product_id = ? AND (r.is_active = 1 OR r.is_active IS NULL)
                ORDER BY r.id ASC
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RecipeItemRow> list = new ArrayList<>();
                while (rs.next()) {
                    double dosage = rs.getDouble("dosage");
                    double lossRate = rs.getDouble("loss_rate");
                    String unit = rs.getString("material_unit");
                    double eff = RecipeItemRow.calcEffectiveDosage(dosage, lossRate, unit);
                    list.add(new RecipeItemRow(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            rs.getString("product_name"),
                            rs.getString("product_type"),
                            rs.getLong("product_price"),
                            rs.getLong("material_id"),
                            rs.getString("material_code"),
                            rs.getString("material_name"),
                            unit,
                            rs.getLong("material_cost_price"),
                            dosage,
                            lossRate,
                            eff,
                            rs.getString("note"),
                            rs.getBoolean("is_active")));
                }
                return list;
            }
        }
    }

    /** Lấy danh sách toàn bộ sản phẩm kèm trạng thái công thức (M-X1). */
    public List<RecipeProductSummary> findAllRecipeSummaries() throws Exception {
        String sql = """
                SELECT p.id AS product_id, p.name AS product_name, p.type AS product_type,
                       p.price AS product_price, p.unit AS product_unit, p.status AS product_status
                FROM dbo.product p
                WHERE p.status = 'ACTIVE' OR p.status IS NULL
                ORDER BY p.type ASC, p.name ASC
                """;

        String recipeSql = """
                SELECT r.id, r.product_id, p.name AS product_name, p.type AS product_type, p.price AS product_price,
                       r.material_id, m.code AS material_code, m.name AS material_name, m.unit AS material_unit,
                       ISNULL(m.cost_price, 0) AS material_cost_price,
                       CAST(r.dosage AS FLOAT) AS dosage,
                       CAST(ISNULL(r.loss_rate, 0) AS FLOAT) AS loss_rate,
                       r.note, ISNULL(r.is_active, 1) AS is_active
                FROM dbo.recipe_item r
                JOIN dbo.product p ON p.id = r.product_id
                JOIN dbo.material m ON m.id = r.material_id
                WHERE r.is_active = 1 OR r.is_active IS NULL
                ORDER BY r.id ASC
                """;

        try (Connection conn = DBContext.getConnection()) {
            java.util.Map<Long, List<RecipeItemRow>> recipeMap = new java.util.HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(recipeSql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pId = rs.getLong("product_id");
                    double dosage = rs.getDouble("dosage");
                    double lossRate = rs.getDouble("loss_rate");
                    String unit = rs.getString("material_unit");
                    double eff = RecipeItemRow.calcEffectiveDosage(dosage, lossRate, unit);
                    recipeMap.computeIfAbsent(pId, k -> new ArrayList<>()).add(new RecipeItemRow(
                            rs.getLong("id"),
                            pId,
                            rs.getString("product_name"),
                            rs.getString("product_type"),
                            rs.getLong("product_price"),
                            rs.getLong("material_id"),
                            rs.getString("material_code"),
                            rs.getString("material_name"),
                            unit,
                            rs.getLong("material_cost_price"),
                            dosage,
                            lossRate,
                            eff,
                            rs.getString("note"),
                            rs.getBoolean("is_active")));
                }
            }

            List<RecipeProductSummary> summaries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pId = rs.getLong("product_id");
                    String pType = rs.getString("product_type");
                    List<RecipeItemRow> items = recipeMap.getOrDefault(pId, List.of());
                    double totalCost = 0;
                    for (RecipeItemRow it : items) {
                        totalCost += it.estimatedCost();
                    }
                    summaries.add(new RecipeProductSummary(
                            pId,
                            rs.getString("product_name"),
                            pType,
                            rs.getLong("product_price"),
                            rs.getString("product_unit"),
                            rs.getString("product_status"),
                            items.size(),
                            totalCost,
                            !items.isEmpty(),
                            RecipeProductSummary.resolveCookingTime(pType),
                            items));
                }
            }
            return summaries;
        }
    }

    /** Lưu công thức cho 1 sản phẩm (M-X2 Editor: Xóa cũ và Insert mới). */
    public void saveRecipe(Connection conn, long productId, List<RecipeItemInput> items) throws Exception {
        String deleteSql = "DELETE FROM dbo.recipe_item WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setLong(1, productId);
            ps.executeUpdate();
        }

        if (items != null && !items.isEmpty()) {
            String insertSql = """
                    INSERT INTO dbo.recipe_item (product_id, material_id, dosage, loss_rate, note, is_active)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (RecipeItemInput item : items) {
                    ps.setLong(1, productId);
                    ps.setLong(2, item.materialId());
                    ps.setDouble(3, item.dosage());
                    ps.setDouble(4, item.lossRate());
                    ps.setString(5, item.note());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    /** Xóa công thức của 1 sản phẩm. */
    public void deleteRecipe(Connection conn, long productId) throws Exception {
        String deleteSql = "DELETE FROM dbo.recipe_item WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setLong(1, productId);
            ps.executeUpdate();
        }
    }

    /** Trừ nguyên liệu kho thô có chốt chặn chống âm kho (Atomic Deduct). */
    public boolean tryDeductMaterial(Connection conn, long branchId, long materialId, double qty) throws Exception {
        String sql = """
                UPDATE dbo.branch_material_inventory
                SET qty = qty - ?, updated_at = SYSUTCDATETIME()
                WHERE branch_id = ? AND material_id = ? AND qty >= ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, qty);
            ps.setLong(2, branchId);
            ps.setLong(3, materialId);
            ps.setDouble(4, qty);
            return ps.executeUpdate() > 0;
        }
    }

    /** Ghi log biến động kho nguyên liệu trong transaction. */
    public void insertMaterialAdjustment(Connection conn, long branchId, long materialId,
            double deltaQty, String reason, String note, long actorId) throws Exception {
        String sql = """
                INSERT INTO dbo.material_adjustment (branch_id, material_id, delta_qty, reason, note, actor_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, SYSUTCDATETIME())
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, materialId);
            ps.setDouble(3, deltaQty);
            ps.setString(4, reason);
            ps.setString(5, note);
            ps.setLong(6, actorId);
            ps.executeUpdate();
        }
    }

    // ================================================================
    // FEATURE 13: CẤU HÌNH VÀ TẠO CẢNH BÁO TỒN KHO & HẠN SỬ DỤNG
    // ================================================================

    /** Lấy số ngày cấu hình cảnh báo cận hạn từ site_settings (mặc định 7 ngày). */
    public int getExpiryWarningDays() throws SQLException {
        String sql = "SELECT setting_value FROM dbo.site_settings WHERE setting_key = 'inventory.expiry_warning_days'";
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                try {
                    return Integer.parseInt(rs.getString("setting_value").trim());
                } catch (Exception ignored) {}
            }
        }
        return 7;
    }

    /** Cấu hình số ngày cảnh báo cận hạn (Row 62: Configure expiry date alerts). */
    public void updateExpiryWarningDays(int days, long actorId) throws SQLException {
        String sql = """
                IF EXISTS (SELECT 1 FROM dbo.site_settings WHERE setting_key = 'inventory.expiry_warning_days')
                    UPDATE dbo.site_settings
                    SET setting_value = ?, updated_at = SYSUTCDATETIME(), updated_by = ?
                    WHERE setting_key = 'inventory.expiry_warning_days'
                ELSE
                    INSERT INTO dbo.site_settings (setting_key, setting_value, updated_at, updated_by)
                    VALUES ('inventory.expiry_warning_days', ?, SYSUTCDATETIME(), ?)
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            String val = String.valueOf(days);
            ps.setString(1, val);
            ps.setLong(2, actorId);
            ps.setString(3, val);
            ps.setLong(4, actorId);
            ps.executeUpdate();
        }
    }

    /** Lấy danh sách ID nhân viên và quản lý phụ trách chi nhánh cần nhận thông báo. */
    public List<Long> findStaffUserIdsForBranch(long branchId) throws SQLException {
        String sql = """
                SELECT DISTINCT s.user_id
                FROM dbo.staff_branch_assignment s
                JOIN dbo.user_account u ON u.id = s.user_id
                WHERE s.branch_id = ? AND s.status = 'ACTIVE' AND u.status = 'ACTIVE'
                UNION
                SELECT DISTINCT id
                FROM dbo.user_account
                WHERE role_code IN ('BRANCH_MANAGER', 'ADMIN') AND status = 'ACTIVE'
                """;
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** Kiểm tra xem thông báo loại này cho món này đã được gửi cho user trong vòng 24h chưa để tránh spam. */
    public boolean hasRecentNotification(long userId, String type, String itemIdentifier) throws SQLException {
        try (Connection conn = DBContext.getConnection()) {
            return hasRecentNotification(conn, userId, type, itemIdentifier);
        }
    }

    public boolean hasRecentNotification(Connection conn, long userId, String type, String itemIdentifier) throws SQLException {
        String sql = """
                SELECT TOP 1 id FROM dbo.notification
                WHERE user_id = ? AND type = ? AND body LIKE ?
                  AND created_at >= DATEADD(hour, -24, SYSUTCDATETIME())
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, type);
            ps.setString(3, "%" + itemIdentifier + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Lấy toàn bộ danh sách cảnh báo tồn kho và cận date tại chi nhánh (Row 61, 63, 64). */
    public List<InventoryAlertItem> findLowStockAndExpiryAlerts(long branchId, int warningDays) throws Exception {
        List<InventoryAlertItem> alerts = new ArrayList<>();

        // 1. Quét sản phẩm thành phẩm
        List<StockRow> products = findByBranch(branchId);
        for (StockRow p : products) {
            if (p.isOutOfStock()) {
                alerts.add(new InventoryAlertItem(
                        "OUT_OF_STOCK", "PRODUCT", p.productId(), p.name(), p.unit(),
                        p.qty(), p.minStock(), p.expiryDate(), p.batchNo(), p.daysUntilExpiry(),
                        "Sản phẩm " + p.name() + " đã hết hàng (tồn kho: 0 " + p.unit() + ")"));
            } else if (p.isLowStock()) {
                alerts.add(new InventoryAlertItem(
                        "LOW_STOCK", "PRODUCT", p.productId(), p.name(), p.unit(),
                        p.qty(), p.minStock(), p.expiryDate(), p.batchNo(), p.daysUntilExpiry(),
                        "Sản phẩm " + p.name() + " sắp hết (còn " + p.qty() + "/" + p.minStock() + " " + p.unit() + ")"));
            }

            if (p.isExpired()) {
                alerts.add(new InventoryAlertItem(
                        "EXPIRED", "PRODUCT", p.productId(), p.name(), p.unit(),
                        p.qty(), p.minStock(), p.expiryDate(), p.batchNo(), p.daysUntilExpiry(),
                        "Sản phẩm " + p.name() + " ĐÃ HẾT HẠN vào ngày " + p.expiryDate() + (p.batchNo() != null ? " (Lô: " + p.batchNo() + ")" : "")));
            } else if (p.isExpiringSoon(warningDays)) {
                alerts.add(new InventoryAlertItem(
                        "EXPIRING_SOON", "PRODUCT", p.productId(), p.name(), p.unit(),
                        p.qty(), p.minStock(), p.expiryDate(), p.batchNo(), p.daysUntilExpiry(),
                        "Sản phẩm " + p.name() + " sắp hết hạn vào ngày " + p.expiryDate() + " (còn " + p.daysUntilExpiry() + " ngày)"));
            }
        }

        // 2. Quét nguyên vật liệu
        List<MaterialStockRow> materials = findMaterialsByBranch(branchId);
        for (MaterialStockRow m : materials) {
            if (m.isOutOfStock()) {
                alerts.add(new InventoryAlertItem(
                        "OUT_OF_STOCK", "MATERIAL", m.materialId(), m.name(), m.unit(),
                        m.qty(), m.minStock(), m.expiryDate(), m.batchNo(), m.daysUntilExpiry(),
                        "Nguyên liệu " + m.name() + " đã hết hàng (tồn kho: 0 " + m.unit() + ")"));
            } else if (m.isLowStock()) {
                alerts.add(new InventoryAlertItem(
                        "LOW_STOCK", "MATERIAL", m.materialId(), m.name(), m.unit(),
                        m.qty(), m.minStock(), m.expiryDate(), m.batchNo(), m.daysUntilExpiry(),
                        "Nguyên liệu " + m.name() + " sắp hết (còn " + m.qty() + "/" + m.minStock() + " " + m.unit() + ")"));
            }

            if (m.isExpired()) {
                alerts.add(new InventoryAlertItem(
                        "EXPIRED", "MATERIAL", m.materialId(), m.name(), m.unit(),
                        m.qty(), m.minStock(), m.expiryDate(), m.batchNo(), m.daysUntilExpiry(),
                        "Nguyên liệu " + m.name() + " ĐÃ HẾT HẠN vào ngày " + m.expiryDate() + (m.batchNo() != null ? " (Lô: " + m.batchNo() + ")" : "")));
            } else if (m.isExpiringSoon(warningDays)) {
                alerts.add(new InventoryAlertItem(
                        "EXPIRING_SOON", "MATERIAL", m.materialId(), m.name(), m.unit(),
                        m.qty(), m.minStock(), m.expiryDate(), m.batchNo(), m.daysUntilExpiry(),
                        "Nguyên liệu " + m.name() + " sắp hết hạn vào ngày " + m.expiryDate() + " (còn " + m.daysUntilExpiry() + " ngày)"));
            }
        }

        return alerts;
    }
}