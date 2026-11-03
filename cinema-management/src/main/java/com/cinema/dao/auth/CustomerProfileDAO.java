package com.cinema.dao.auth;

import com.cinema.model.auth.CustomerProfile;

import com.cinema.infrastructure.database.DBContext;

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
        String sql = "SELECT user_id, points, tier, bank_name, bank_account_name, bank_account_number "
                + "FROM dbo.customer_profile WHERE user_id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    CustomerProfile profile = new CustomerProfile(rs.getLong("user_id"));
                    profile.setPoints(rs.getInt("points"));
                    profile.setTier(rs.getString("tier"));
                    profile.setBankName(rs.getString("bank_name"));
                    profile.setBankAccountName(rs.getString("bank_account_name"));
                    profile.setBankAccountNumber(rs.getString("bank_account_number"));
                    return Optional.of(profile);
                }
            }
        }
        return Optional.empty();
    }

    public void updateBankAccount(Long userId, String bankName, String bankAccountName,
                                  String bankAccountNumber) throws Exception {
        String sql = "UPDATE dbo.customer_profile SET bank_name = ?, bank_account_name = ?, "
                + "bank_account_number = ? WHERE user_id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bankName);
            ps.setString(2, bankAccountName);
            ps.setString(3, bankAccountNumber);
            ps.setLong(4, userId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("Customer profile does not exist for user " + userId);
            }
        }
    }

    /**
     * Update customer points balance.
     */
    public void updatePoints(Long userId, int points) throws Exception {
        String sql = "UPDATE dbo.customer_profile SET points = ? WHERE user_id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, points);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Update customer tier (membership level).
     */
    public void updateTier(Long userId, String tier) throws Exception {
        String sql = "UPDATE dbo.customer_profile SET tier = ? WHERE user_id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tier);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }
}
