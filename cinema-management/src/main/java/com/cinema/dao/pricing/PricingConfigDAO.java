package com.cinema.dao.pricing;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Reads the explicitly configured fallback ticket price. */
public class PricingConfigDAO {
    public long getDefaultTicketPrice() throws Exception {
        String sql = "SELECT default_ticket_price FROM dbo.pricing_config WHERE id = 1";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("Chưa cấu hình giá vé mặc định trong pricing_config");
            }
            return rs.getLong("default_ticket_price");
        }
    }

    public long updateDefaultTicketPrice(long price) throws Exception {
        String sql = "UPDATE dbo.pricing_config SET default_ticket_price = ?, updated_at = SYSUTCDATETIME() WHERE id = 1";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, price);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("Chưa cấu hình giá vé mặc định trong pricing_config");
            }
            return price;
        }
    }
}
