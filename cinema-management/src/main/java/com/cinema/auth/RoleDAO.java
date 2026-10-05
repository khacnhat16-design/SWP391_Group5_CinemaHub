package com.cinema.auth;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/** Data access for role reference table. */
public class RoleDAO {

    /**
     * Find role by code.
     */
    public Optional<Role> findByCode(String code) throws Exception {
        String sql = "SELECT code FROM dbo.role WHERE code = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(Role.valueOf(rs.getString("code")));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Check if role exists.
     */
    public boolean exists(String code) throws Exception {
        return findByCode(code).isPresent();
    }
}
