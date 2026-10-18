package com.cinema.dao.pricing;

import com.cinema.model.pricing.Holiday;

import com.cinema.infrastructure.database.DBContext;
// import dal.DBContext;

// import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Data access cho bÃƒÂ¡Ã‚ÂºÃ‚Â£ng dbo.holiday (template ngÃƒÆ’Ã‚Â y lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¦).
 * Spec: .kiro/specs/holiday-vouchers/ Ãƒâ€šÃ‚Â§2.2.
 */
public class HolidayDAO {

    private static final String BASE_COLUMNS = """
            id, name, month_day, discount_type, discount_value, min_order_amount,
            max_uses, refundable, valid_days, enabled, created_by, created_at, updated_at
            """;

    /** Insert mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â set generated id lÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒÆ’Ã‚Âªn entity. */
    public void insert(Holiday holiday) throws Exception {
        String sql = """
            INSERT INTO dbo.holiday (name, month_day, discount_type, discount_value, min_order_amount,
                                    max_uses, refundable, valid_days, enabled, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSUTCDATETIME(), SYSUTCDATETIME())
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, holiday.name());
            ps.setString(2, holiday.monthDay());
            ps.setString(3, holiday.discountType());
            ps.setLong(4, holiday.discountValue());
            ps.setLong(5, holiday.minOrderAmount());
            ps.setInt(6, holiday.maxUses());
            ps.setBoolean(7, holiday.refundable());
            ps.setInt(8, holiday.validDays());
            ps.setBoolean(9, holiday.enabled());
            if (holiday.createdBy() == null) ps.setNull(10, Types.BIGINT);
            else ps.setLong(10, holiday.createdBy());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) holiday.setId(rs.getLong(1));
            }
        }
    }

    public Optional<Holiday> findById(long id) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.holiday WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Holiday> findByName(String name) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.holiday WHERE name = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public List<Holiday> findAll() throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.holiday ORDER BY month_day ASC, id ASC";
        List<Holiday> list = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    public Set<String> findEnabledMonthDays() throws Exception {
        String sql = "SELECT month_day FROM dbo.holiday WHERE enabled = 1";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Set<String> monthDays = new java.util.HashSet<>();
            while (rs.next()) {
                monthDays.add(rs.getString("month_day"));
            }
            return Set.copyOf(monthDays);
        }
    }

    /** TÃƒÆ’Ã‚Â¬m cÃƒÆ’Ã‚Â¡c holiday cÃƒÆ’Ã‚Â³ enabled = 1 theo danh sÃƒÆ’Ã‚Â¡ch id. TrÃƒÂ¡Ã‚ÂºÃ‚Â£ vÃƒÂ¡Ã‚Â»Ã‚Â list (cÃƒÆ’Ã‚Â³ thÃƒÂ¡Ã‚Â»Ã†â€™ ÃƒÆ’Ã‚Â­t hÃƒâ€ Ã‚Â¡n inputIds). */
    public List<Holiday> findByIdsEnabled(List<Long> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        StringBuilder sb = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM dbo.holiday WHERE enabled = 1 AND id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            List<Holiday> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
            return list;
        }
    }

    /** Update full row (KHÃƒÆ’Ã¢â‚¬ÂNG update created_at). */
    public void update(Holiday holiday) throws Exception {
        String sql = """
            UPDATE dbo.holiday
            SET name = ?, month_day = ?, discount_type = ?, discount_value = ?,
                min_order_amount = ?, max_uses = ?, refundable = ?, valid_days = ?,
                enabled = ?, updated_at = SYSUTCDATETIME()
            WHERE id = ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, holiday.name());
            ps.setString(2, holiday.monthDay());
            ps.setString(3, holiday.discountType());
            ps.setLong(4, holiday.discountValue());
            ps.setLong(5, holiday.minOrderAmount());
            ps.setInt(6, holiday.maxUses());
            ps.setBoolean(7, holiday.refundable());
            ps.setInt(8, holiday.validDays());
            ps.setBoolean(9, holiday.enabled());
            ps.setLong(10, holiday.id());
            ps.executeUpdate();
        }
    }

    /** Soft delete: enabled = 0. Voucher Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ sinh tÃƒÂ¡Ã‚Â»Ã‚Â« holiday nÃƒÆ’Ã‚Â y vÃƒÂ¡Ã‚ÂºÃ‚Â«n hoÃƒÂ¡Ã‚ÂºÃ‚Â¡t Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ng bÃƒÆ’Ã‚Â¬nh thÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âng. */
    public void disable(long id) throws Exception {
        String sql = "UPDATE dbo.holiday SET enabled = 0, updated_at = SYSUTCDATETIME() WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private Holiday mapRow(ResultSet rs) throws SQLException {
        Holiday h = new Holiday();
        h.setId(rs.getLong("id"));
        h.setName(rs.getString("name"));
        h.setMonthDay(rs.getString("month_day"));
        h.setDiscountType(rs.getString("discount_type"));
        h.setDiscountValue(rs.getLong("discount_value"));
        h.setMinOrderAmount(rs.getLong("min_order_amount"));
        h.setMaxUses(rs.getInt("max_uses"));
        h.setRefundable(rs.getBoolean("refundable"));
        h.setValidDays(rs.getInt("valid_days"));
        h.setEnabled(rs.getBoolean("enabled"));
        long createdBy = rs.getLong("created_by");
        h.setCreatedBy(rs.wasNull() ? null : createdBy);
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) h.setCreatedAt(created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) h.setUpdatedAt(updated.toLocalDateTime());
        return h;
    }
}
