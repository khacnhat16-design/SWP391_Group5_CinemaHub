package com.cinema.dao.pricing;

import com.cinema.model.branch.Branch;
import com.cinema.model.movie.Movie;
import com.cinema.model.pricing.PriceRule;
import com.cinema.model.pricing.PriceRules;
import com.cinema.model.pricing.PricingEngine;
import com.cinema.model.pricing.TicketType;
import com.cinema.model.showtime.Showtime;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access cho {@code dbo.price_rule} (Req 5.1-5.5, 7 — dynamic pricing).
 *
 * <p>Backward compatible: rule cũ (chỉ seat_type/day_type/time_slot) vẫn được load
 * bình thường với các cột scope và ngày hiệu lực = NULL. Admin có thể tạo rule mới với
 * movie_id/branch_id/showtime_id/ticket_type override.</p>
 */
public class PriceRuleDAO {

    private static final String COLUMNS =
            "id, seat_type, day_type, time_slot, price, movie_id, branch_id, showtime_id, ticket_type, active_from, active_to";

    /**
     * Tạo khung giá. Có thể override theo Movie / Branch / Showtime / TicketType — chỉ một
     * trong movie/branch/showtime được phép NOT NULL (xem CK_price_rule.check_constraint trong
     * migration_dynamic_pricing.sql).
     */
    public void insert(PriceRule rule) throws Exception {
        String sql = """
            INSERT INTO dbo.price_rule
                (seat_type, day_type, time_slot, price, movie_id, branch_id, showtime_id, ticket_type,
                 active_from, active_to)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rule.seatType());
            ps.setString(2, rule.dayType());
            ps.setString(3, rule.timeSlot());
            ps.setLong(4, rule.price());
            setNullableLong(ps, 5, rule.movieId());
            setNullableLong(ps, 6, rule.branchId());
            setNullableLong(ps, 7, rule.showtimeId());
            if (rule.ticketType() == null || rule.ticketType().isBlank()) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, rule.ticketType());
            }
            setNullableDate(ps, 9, rule.activeFrom());
            setNullableDate(ps, 10, rule.activeTo());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    rule.setId(rs.getLong(1));
                }
            }
        }
    }

    /** Insert a movie-and-branch rule inside a caller-managed transaction. */
    public void insert(Connection conn, PriceRule rule) throws Exception {
        String sql = """
            INSERT INTO dbo.price_rule
                (seat_type, day_type, time_slot, price, movie_id, branch_id, showtime_id, ticket_type,
                 active_from, active_to)
            VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rule.seatType());
            ps.setString(2, rule.dayType());
            ps.setString(3, rule.timeSlot());
            ps.setLong(4, rule.price());
            ps.setLong(5, rule.movieId());
            ps.setLong(6, rule.branchId());
            if (rule.ticketType() == null || rule.ticketType().isBlank()) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, rule.ticketType());
            }
            setNullableDate(ps, 8, rule.activeFrom());
            setNullableDate(ps, 9, rule.activeTo());
            ps.executeUpdate();
        }
    }

    /** Read and lock a movie-and-branch rule set for an atomic template application. */
    public List<PriceRule> findMovieBranch(Connection conn, long movieId, long branchId)
            throws Exception {
        String sql = "SELECT " + COLUMNS + "\n"
                + "FROM dbo.price_rule WITH (UPDLOCK, HOLDLOCK) "
                + "WHERE movie_id = ? AND branch_id = ? AND showtime_id IS NULL "
                + "ORDER BY id";
        List<PriceRule> rules = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            ps.setLong(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rules.add(mapRow(rs));
                }
            }
        }
        return rules;
    }

    /** Delete only the selected movie-and-branch rules in the caller's transaction. */
    public int deleteMovieBranch(Connection conn, long movieId, long branchId) throws Exception {
        String sql = """
                DELETE FROM dbo.price_rule
                WHERE movie_id = ? AND branch_id = ? AND showtime_id IS NULL
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            ps.setLong(2, branchId);
            return ps.executeUpdate();
        }
    }

    public Optional<PriceRule> findById(Long id) throws Exception {
        String sql = "SELECT " + COLUMNS + " FROM dbo.price_rule WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Req 5.1 — tra theo tổ hợp seat_type × day_type × time_slot (scope = SYSTEM). */
    public Optional<PriceRule> findCombo(String seatType, String dayType, String timeSlot) throws Exception {
        String sql = """
            SELECT """ + COLUMNS + """
            FROM dbo.price_rule
            WHERE seat_type = ? AND day_type = ? AND time_slot = ?
              AND movie_id IS NULL AND branch_id IS NULL AND showtime_id IS NULL
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seatType);
            ps.setString(2, dayType);
            ps.setString(3, timeSlot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Toàn bộ khung giá — dùng nạp cache PriceRules cho PricingEngine. */
    public List<PriceRule> findAll() throws Exception {
        String sql = "SELECT " + COLUMNS + " FROM dbo.price_rule ORDER BY seat_type, day_type, time_slot";
        List<PriceRule> rules = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rules.add(mapRow(rs));
            }
        }
        return rules;
    }

    /** Cập nhật giá (Req 5.4 — chỉ áp dụng cho vé tạo sau, không hồi tố ticket_seat.price). */
    public void updatePrice(long ruleId, long price) throws Exception {
        String sql = "UPDATE dbo.price_rule SET price = ? WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, price);
            ps.setLong(2, ruleId);
            ps.executeUpdate();
        }
    }

    public void updatePriceAndDates(long ruleId, long price, LocalDate activeFrom,
                                    LocalDate activeTo) throws Exception {
        String sql = "UPDATE dbo.price_rule SET price = ?, active_from = ?, active_to = ? WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, price);
            setNullableDate(ps, 2, activeFrom);
            setNullableDate(ps, 3, activeTo);
            ps.setLong(4, ruleId);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("Khung giá không còn tồn tại");
            }
        }
    }

    public boolean delete(long ruleId) throws Exception {
        String sql = "DELETE FROM dbo.price_rule WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ruleId);
            return ps.executeUpdate() == 1;
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private PriceRule mapRow(ResultSet rs) throws Exception {
        PriceRule rule = new PriceRule();
        rule.setId(rs.getLong("id"));
        rule.setSeatType(rs.getString("seat_type"));
        rule.setDayType(rs.getString("day_type"));
        rule.setTimeSlot(rs.getString("time_slot"));
        rule.setPrice(rs.getLong("price"));
        rule.setMovieId(getNullableLong(rs, "movie_id"));
        rule.setBranchId(getNullableLong(rs, "branch_id"));
        rule.setShowtimeId(getNullableLong(rs, "showtime_id"));
        String tt = rs.getString("ticket_type");
        rule.setTicketType(tt);
        Date activeFrom = rs.getDate("active_from");
        rule.setActiveFrom(activeFrom == null ? null : activeFrom.toLocalDate());
        Date activeTo = rs.getDate("active_to");
        rule.setActiveTo(activeTo == null ? null : activeTo.toLocalDate());
        return rule;
    }

    private static void setNullableDate(PreparedStatement ps, int index, LocalDate value)
            throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.DATE);
        else ps.setDate(index, Date.valueOf(value));
    }

    private static Long getNullableLong(ResultSet rs, String column) throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}