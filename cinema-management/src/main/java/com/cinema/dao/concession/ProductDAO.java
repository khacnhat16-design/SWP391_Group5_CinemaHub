package com.cinema.dao.concession;

import com.cinema.model.concession.Product;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for product + combo_item (Req 19.1-19.3, 19.8). */
public class ProductDAO {

    private static final String BASE_COLUMNS = """
        id, name, description, price, unit, type, status, version
        """;

    /** Insert new product; sets generated id. UNIQUE(name) chặn trùng ở DB. */
    public void insert(Product product) throws Exception {
        String sql = """
            INSERT INTO dbo.product (name, description, price, unit, type, status, version)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, product.name());
            ps.setString(2, product.description());
            ps.setLong(3, product.price());
            ps.setString(4, product.unit());
            ps.setString(5, product.type());
            ps.setString(6, product.status());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) product.setId(rs.getLong(1));
            }
        }
    }

    public Optional<Product> findById(Long id) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.product WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public Optional<Product> findByName(String name) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.product WHERE name = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /** Danh mục cho Admin (mọi trạng thái) và POS (chỉ ACTIVE). */
    public List<Product> findAll(boolean activeOnly) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.product "
                + (activeOnly ? "WHERE status = 'ACTIVE' " : "")
                + "ORDER BY type, name";
        List<Product> products = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) products.add(mapRow(rs));
        }
        return products;
    }

    /**
     * Cập nhật giá bán (Req 19.8) — giá đã ghi trên concession_order_line là snapshot,
     * update ở đây chỉ áp cho đơn tạo sau.
     */
    public void update(Product product) throws Exception {
        String sql = """
            UPDATE dbo.product
            SET name = ?, description = ?, price = ?, unit = ?, type = ?, status = ?,
                version = version + 1
            WHERE id = ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, product.name());
            ps.setString(2, product.description());
            ps.setLong(3, product.price());
            ps.setString(4, product.unit());
            ps.setString(5, product.type());
            ps.setString(6, product.status());
            ps.setLong(7, product.id());
            ps.executeUpdate();
        }
    }

    public void updateStatus(long productId, String status) throws Exception {
        String sql = "UPDATE dbo.product SET status = ?, version = version + 1 WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, productId);
            ps.executeUpdate();
        }
    }

    // ---- combo_item ----

    /** Thêm thành phần combo (Req 19.2). */
    public void insertComboItem(long comboId, long productId, int qty) throws Exception {
        String sql = "INSERT INTO dbo.combo_item (combo_id, product_id, qty) VALUES (?, ?, ?)";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, comboId);
            ps.setLong(2, productId);
            ps.setInt(3, qty);
            ps.executeUpdate();
        }
    }

    public List<Product.ComboComponent> findComboItems(long comboId) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return findComboItems(conn, comboId);
        }
    }

    public List<Product.ComboComponent> findComboItems(Connection conn, long comboId) throws Exception {
        String sql = """
            SELECT ci.product_id, ci.qty, p.name
            FROM dbo.combo_item ci
            JOIN dbo.product p ON p.id = ci.product_id
            WHERE ci.combo_id = ? ORDER BY p.name
            """;
        List<Product.ComboComponent> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, comboId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new Product.ComboComponent(
                            rs.getLong("product_id"), rs.getString("name"), rs.getInt("qty")));
                }
            }
        }
        return items;
    }

    public Optional<Product> findById(Connection conn, long id) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.product WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Các combo ACTIVE chứa sản phẩm này (Req 19.3) — chặn deactivate product
     * khi còn nằm trong combo hiệu lực.
     */
    public List<Product> findActiveCombosContaining(long productId) throws Exception {
        String sql = """
            SELECT p.id, p.name, p.description, p.price, p.unit, p.type, p.status, p.version
            FROM dbo.combo_item ci
            JOIN dbo.product p ON p.id = ci.combo_id
            WHERE ci.product_id = ? AND p.status = 'ACTIVE' AND p.type = 'COMBO'
            """;
        List<Product> combos = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) combos.add(mapRow(rs));
            }
        }
        return combos;
    }

    private Product mapRow(ResultSet rs) throws Exception {
        Product product = new Product();
        product.setId(rs.getLong("id"));
        product.setName(rs.getString("name"));
        product.setDescription(rs.getString("description"));
        product.setPrice(rs.getLong("price"));
        product.setUnit(rs.getString("unit"));
        product.setType(rs.getString("type"));
        product.setStatus(rs.getString("status"));
        product.setVersion(rs.getInt("version"));
        return product;
    }
}
