package com.cinema.dao.pricing;

import com.cinema.model.pricing.*;
import com.cinema.infrastructure.database.DBContext;
// import dal.DBContext;

// import dal.DBContext;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC persistence for reusable ticket-price templates. */
public class PriceTemplateDAO {

    public record Summary(long id, String name, String description, String sourceType, int ruleCount,
                          LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public List<Summary> findAll() throws Exception {
        String sql = """
                SELECT t.id, t.name, t.description, t.source_type, COUNT(r.id) AS rule_count,
                       t.created_at, t.updated_at
                FROM dbo.ticket_price_template t
                LEFT JOIN dbo.ticket_price_template_rule r ON r.template_id = t.id
                GROUP BY t.id, t.name, t.description, t.source_type,
                         t.created_at, t.updated_at
                ORDER BY t.name, t.id
                """;
        List<Summary> summaries = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                summaries.add(new Summary(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("source_type"), rs.getInt("rule_count"),
                        localDateTime(rs.getTimestamp("created_at")),
                        localDateTime(rs.getTimestamp("updated_at"))));
            }
        }
        return summaries;
    }

    public Optional<PriceTemplate> findById(long id) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return findById(conn, id);
        }
    }

    public Optional<PriceTemplate> findById(Connection conn, long id) throws Exception {
        String sql = """
                SELECT id, name, description, source_type, created_by, created_at, updated_at
                FROM dbo.ticket_price_template WHERE id = ?
                """;
        PriceTemplate template;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                template = new PriceTemplate();
                template.setId(rs.getLong("id"));
                template.setName(rs.getString("name"));
                template.setDescription(rs.getString("description"));
                template.setSourceType(rs.getString("source_type"));
                template.setCreatedBy(nullableLong(rs, "created_by"));
                template.setCreatedAt(localDateTime(rs.getTimestamp("created_at")));
                template.setUpdatedAt(localDateTime(rs.getTimestamp("updated_at")));
            }
        }
        template.setRules(findRules(conn, id));
        return Optional.of(template);
    }

    public long insertHeader(Connection conn, PriceTemplate template) throws Exception {
        String sql = """
                INSERT INTO dbo.ticket_price_template (name, description, source_type, created_by)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, template.getName());
            ps.setString(2, template.getDescription());
            ps.setString(3, template.getSourceType());
            if (template.getCreatedBy() == null) ps.setNull(4, Types.BIGINT);
            else ps.setLong(4, template.getCreatedBy());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new IllegalStateException("KhÃ´ng láº¥y Ä‘Æ°á»£c ID máº«u giÃ¡ vá»«a táº¡o");
                return rs.getLong(1);
            }
        }
    }

    public boolean updateHeader(Connection conn, long id, String name, String description)
            throws Exception {
        String sql = """
                UPDATE dbo.ticket_price_template
                SET name = ?, description = ?, updated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    public void replaceRules(Connection conn, long templateId, List<PriceTemplateRule> rules)
            throws Exception {
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM dbo.ticket_price_template_rule WHERE template_id = ?")) {
            delete.setLong(1, templateId);
            delete.executeUpdate();
        }
        String sql = """
                INSERT INTO dbo.ticket_price_template_rule
                    (template_id, seat_type, day_type, time_slot, price, ticket_type, active_from, active_to)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PriceTemplateRule rule : rules) {
                ps.setLong(1, templateId);
                ps.setString(2, rule.getSeatType());
                ps.setString(3, rule.getDayType());
                ps.setString(4, rule.getTimeSlot());
                ps.setLong(5, rule.getPrice());
                if (rule.getTicketType() == null) ps.setNull(6, Types.VARCHAR);
                else ps.setString(6, rule.getTicketType());
                setNullableDate(ps, 7, rule.getActiveFrom());
                setNullableDate(ps, 8, rule.getActiveTo());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public boolean delete(Connection conn, long id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM dbo.ticket_price_template WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    private List<PriceTemplateRule> findRules(Connection conn, long templateId) throws Exception {
        String sql = """
                SELECT id, seat_type, day_type, time_slot, price, ticket_type, active_from, active_to
                FROM dbo.ticket_price_template_rule
                WHERE template_id = ?
                ORDER BY seat_type, day_type, time_slot, ticket_type, active_from, active_to
                """;
        List<PriceTemplateRule> rules = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PriceTemplateRule rule = new PriceTemplateRule();
                    rule.setId(rs.getLong("id"));
                    rule.setSeatType(rs.getString("seat_type"));
                    rule.setDayType(rs.getString("day_type"));
                    rule.setTimeSlot(rs.getString("time_slot"));
                    rule.setPrice(rs.getLong("price"));
                    rule.setTicketType(rs.getString("ticket_type"));
                    rule.setActiveFrom(localDate(rs.getDate("active_from")));
                    rule.setActiveTo(localDate(rs.getDate("active_to")));
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    private static Long nullableLong(ResultSet rs, String column) throws Exception {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static void setNullableDate(PreparedStatement ps, int index, LocalDate value)
            throws Exception {
        if (value == null) ps.setNull(index, Types.DATE);
        else ps.setDate(index, Date.valueOf(value));
    }
}
