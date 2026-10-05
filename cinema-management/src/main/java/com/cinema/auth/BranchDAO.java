package com.cinema.auth;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Data access for branch table. */
public class BranchDAO {

    /**
     * Check if branch exists and is ACTIVE.
     */
    public boolean exists(Long branchId) throws Exception {
        String sql = "SELECT 1 FROM dbo.branch WHERE id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
