package com.cinema.dao.inventory;

import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.inventory.Product;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for product (Giai đoạn 1 - M-P1). */
public class ProductDAO {

    private static final String BASE_COLUMNS = "id, name, description, price, unit, type, status, version";

    public Optional<Product> findById(Long id) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.product WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Product p = new Product();
                    p.setId(rs.getLong("id"));
                    p.setName(rs.getString("name"));
                    p.setDescription(rs.getString("description"));
                    p.setPrice(rs.getLong("price"));
                    p.setUnit(rs.getString("unit"));
                    p.setType(rs.getString("type"));
                    p.setStatus(rs.getString("status"));
                    p.setVersion(rs.getInt("version"));
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    public List<Product> findAllActive() throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.product WHERE status = 'ACTIVE' ORDER BY name";
        List<Product> list = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Product p = new Product();
                p.setId(rs.getLong("id"));
                p.setName(rs.getString("name"));
                p.setDescription(rs.getString("description"));
                p.setPrice(rs.getLong("price"));
                p.setUnit(rs.getString("unit"));
                p.setType(rs.getString("type"));
                p.setStatus(rs.getString("status"));
                p.setVersion(rs.getInt("version"));
                list.add(p);
            }
        }
        return list;
    }
}
