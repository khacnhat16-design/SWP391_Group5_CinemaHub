package com.cinema.infrastructure.auth;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Seed role data if empty. */
public class SeedRoles {
    public static void seedIfEmpty() throws Exception {
        String sql = "SELECT COUNT(*) FROM dbo.role";
        
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            
            rs.next();
            if (rs.getInt(1) == 0) {
                seedRoles(conn);
            }
        }
    }

    private static void seedRoles(Connection conn) throws Exception {
        String[][] roles = {
            {"ADMIN", "Quản trị hệ thống"},
            {"BRANCH_MANAGER", "Quản lý chi nhánh"},
            {"BRANCH_STAFF", "Nhân viên chi nhánh"},
            {"CUSTOMER", "Khách hàng"}
        };
        
        String sql = "INSERT INTO dbo.role (code, display_name) VALUES (?, ?)";
        
        for (String[] role : roles) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, role[0]);
                ps.setString(2, role[1]);
                ps.executeUpdate();
            }
        }
    }
}
