package com.cinema.dao.voucher;

import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.voucher.Voucher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** DAO don gian cho voucher: doc theo code, list, insert, tang used_count. */
public class VoucherDAO {

    private Voucher map(ResultSet rs) throws SQLException {
        Voucher v = new Voucher();
        v.setId(rs.getLong("id"));
        v.setCode(rs.getString("code"));
        v.setDiscountType(rs.getString("discount_type"));
        v.setDiscountValue(rs.getLong("discount_value"));
        v.setMinOrderAmount(rs.getLong("min_order_amount"));
        Timestamp from = rs.getTimestamp("valid_from");
        Timestamp to = rs.getTimestamp("valid_to");
        if (from != null) v.setValidFrom(from.toLocalDateTime());
        if (to != null) v.setValidTo(to.toLocalDateTime());
        v.setMaxUses(rs.getInt("max_uses"));
        v.setUsedCount(rs.getInt("used_count"));
        v.setStatus(rs.getString("status"));
        return v;
    }

    public Optional<Voucher> findByCode(String code) throws SQLException {
        if (code == null || code.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM dbo.voucher WHERE code = ?";
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Voucher> findAll() throws SQLException {
        String sql = "SELECT * FROM dbo.voucher ORDER BY id DESC";
        List<Voucher> out = new ArrayList<>();
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    public Voucher insert(Voucher v) throws SQLException {
        String sql = """
            INSERT INTO dbo.voucher
              (code, discount_type, discount_value, min_order_amount,
               valid_from, valid_to, max_uses, used_count, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE')
            """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, v.code());
            ps.setString(2, v.discountType());
            ps.setLong(3, v.discountValue());
            ps.setLong(4, v.minOrderAmount());
            ps.setTimestamp(5, Timestamp.valueOf(v.validFrom()));
            ps.setTimestamp(6, Timestamp.valueOf(v.validTo()));
            ps.setInt(7, v.maxUses());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) v.setId(rs.getLong(1));
            }
        }
        return v;
    }

    /** Tang used_count +1, chi thanh cong khi con quota (chong dung qua gioi han). */
    public boolean useOnce(Connection conn, long voucherId) throws SQLException {
        String sql = """
            UPDATE dbo.voucher SET used_count = used_count + 1
            WHERE id = ? AND status = 'ACTIVE' AND used_count < max_uses
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, voucherId);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean setStatus(long id, String status) throws SQLException {
        String sql = "UPDATE dbo.voucher SET status = ? WHERE id = ?";
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        }
    }
}
