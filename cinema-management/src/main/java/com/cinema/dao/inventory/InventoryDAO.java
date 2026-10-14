package com.cinema.dao.inventory;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Data access for branch_inventory (Giai đoạn 1 - Màn 1: M-P1). */
public class InventoryDAO {

    public static record StockRow(
            long productId,
            String name,
            long price,
            String unit,
            String type,
            String status,
            int qty,
            String updatedAt
    ) {}

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

    /** Toàn bộ tồn kho bắp nước của chi nhánh (Màn 1 - M-P1). */
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
}
