package com.cinema.auth;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/** Data access for customer_profile table. */
public class CustomerProfileDAO {

    /**
     * Insert a new customer profile.
     */
    public void insert(CustomerProfile profile) throws Exception {
        String sql = "INSERT INTO dbo.customer_profile (user_id, points, tier) VALUES (?, ?, ?)";

        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, profile.userId());
            ps.setInt(2, profile.points());
            ps.setString(3, profile.tier());
            ps.executeUpdate();
        }
    }

    /**
     * Find customer profile by user ID.
     */
    public Optional<CustomerProfile> findByUserId(Long userId) throws Exception {
        String sql = "SELECT user_id, points, tier FROM dbo.customer_profile WHERE user_id = ?";

        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    CustomerProfile profile = new CustomerProfile(rs.getLong("user_id"));
                    profile.setPoints(rs.getInt("points"));
                    profile.setTier(rs.getString("tier"));
                    return Optional.of(profile);
                }
            }
        }
        return Optional.empty();
    }
}
