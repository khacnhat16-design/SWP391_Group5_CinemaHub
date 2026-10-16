package com.cinema.dao.site;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SiteSettingDAO {

    private static final String SQL_GET =
            "SELECT setting_value FROM dbo.site_settings WHERE setting_key = ?";

    private static final String SQL_UPSERT =
            """
            MERGE dbo.site_settings AS target
            USING (SELECT ? AS setting_key) AS source
            ON target.setting_key = source.setting_key
            WHEN MATCHED THEN
                UPDATE SET setting_value = ?, updated_at = SYSUTCDATETIME()
            WHEN NOT MATCHED THEN
                INSERT (setting_key, setting_value, updated_at)
                VALUES (?, ?, SYSUTCDATETIME());
            """;

    public String get(String key) {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_GET)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("setting_value");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read site_setting: " + key, e);
        }
        return null;
    }

    public void upsert(String key, String value, Long actorId) {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert site_setting: " + key, e);
        }
    }

    public void upsert(String key, String value) {
        upsert(key, value, null);
    }
}
