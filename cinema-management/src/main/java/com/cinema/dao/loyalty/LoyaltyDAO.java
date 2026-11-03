package com.cinema.dao.loyalty;

import com.cinema.model.loyalty.LoyaltyTier;
import com.cinema.model.loyalty.PointLedger;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for loyalty_config, customer_profile (points/tier) và point_ledger (Req 17). */
public class LoyaltyDAO {

    // ---- loyalty_config ----

    /** Insert tier config; sets generated id. */
    public void insertTier(LoyaltyTier tier) throws Exception {
        String sql = """
            INSERT INTO dbo.loyalty_config (tier_name, min_points, discount_percent, point_rate)
            VALUES (?, ?, ?, ?)
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tier.tierName());
            ps.setInt(2, tier.minPoints());
            ps.setInt(3, tier.discountPercent());
            ps.setDouble(4, tier.pointRate());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) tier.setId(rs.getLong(1));
            }
        }
    }

    public List<LoyaltyTier> findAllTiers() throws Exception {
        String sql = """
            SELECT id, tier_name, min_points, discount_percent, point_rate
            FROM dbo.loyalty_config ORDER BY min_points
            """;
        List<LoyaltyTier> tiers = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) tiers.add(mapTier(rs));
        }
        return tiers;
    }

    public List<LoyaltyTier> findAllTiers(Connection conn) throws Exception {
        String sql = """
            SELECT id, tier_name, min_points, discount_percent, point_rate
            FROM dbo.loyalty_config ORDER BY min_points
            """;
        List<LoyaltyTier> tiers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) tiers.add(mapTier(rs));
        }
        return tiers;
    }

    public Optional<LoyaltyTier> findTierByName(String tierName) throws Exception {
        String sql = """
            SELECT id, tier_name, min_points, discount_percent, point_rate
            FROM dbo.loyalty_config WHERE tier_name = ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tierName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapTier(rs));
            }
        }
        return Optional.empty();
    }

    // ---- customer_profile points/tier ----

    public Optional<Integer> findPoints(long userId) throws Exception {
        String sql = "SELECT points FROM dbo.customer_profile WHERE user_id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getInt("points"));
            }
        }
        return Optional.empty();
    }

    public Optional<String> findTier(long userId) throws Exception {
        String sql = "SELECT tier FROM dbo.customer_profile WHERE user_id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("tier"));
            }
        }
        return Optional.empty();
    }

    public Optional<String> findTier(Connection conn, long userId) throws Exception {
        String sql = "SELECT tier FROM dbo.customer_profile WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString("tier"));
            }
        }
        return Optional.empty();
    }

    /**
     * Cộng/trừ điểm nguyên tử (Req 17.6 — design.md):
     * UPDATE points = points + delta trong connection của caller. Hai giao dịch cộng
     * điểm đồng thời serialize trên row lock — tổng cuối bằng tổng hai delta, không mất cập nhật.
     * allowNegative=false chặn trừ vượt số dư (affectedRows==0).
     *
     * @return số dư điểm sau cập nhật
     */
    public int adjustPoints(Connection conn, long userId, int delta, boolean allowNegative) throws Exception {
        // Nguyên tử: 1 câu UPDATE ... OUTPUT Inserted.points — không có cửa sổ race giữa
        // UPDATE và SELECT như 2-round-trips trước. UPDLOCK + ROWLOCK serialize các giao dịch
        // cùng user_id. allowNegative=false chặn trừ vượt số dư bằng WHERE points + delta >= 0.
        String sql = allowNegative
                ? """
                  UPDATE dbo.customer_profile WITH (UPDLOCK, ROWLOCK)
                  SET points = points + ?
                  OUTPUT Inserted.points
                  WHERE user_id = ?
                  """
                : """
                  UPDATE dbo.customer_profile WITH (UPDLOCK, ROWLOCK)
                  SET points = points + ?
                  OUTPUT Inserted.points
                  WHERE user_id = ? AND points + ? >= 0
                  """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, userId);
            if (!allowNegative) ps.setInt(3, delta);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                return -1; // guard thất bại (không đủ điểm khi cấm âm hoặc user không tồn tại)
            }
        }
    }

    /** Nâng hạng thành viên khi điểm vượt ngưỡng (Req 17.3). */
    public Optional<Integer> findPoints(Connection conn, long userId) throws Exception {
        String sql = "SELECT points FROM dbo.customer_profile WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getInt("points"));
            }
        }
        return Optional.empty();
    }

    public void updateTier(long userId, String tierName) throws Exception {
        String sql = "UPDATE dbo.customer_profile SET tier = ? WHERE user_id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tierName);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void updateTier(Connection conn, long userId, String tierName) throws Exception {
        String sql = "UPDATE dbo.customer_profile SET tier = ? WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tierName);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Chi nhánh "chính" của customer = chi nhánh của vé / đơn F&B gần nhất.
     * Customer không có branch_id cố định; chỉ suy ra từ lịch sử giao dịch.
     * Trả về null nếu customer chưa từng giao dịch.
     */
    public Long findPrimaryBranchId(long userId) throws Exception {
        String sql = """
            SELECT TOP 1 branch_id FROM (
                SELECT t.branch_id, COALESCE(t.confirmed_at, t.created_at) AS activity_at
                FROM dbo.ticket t
                  WHERE t.user_id = ? AND t.branch_id IS NOT NULL
                UNION ALL
                SELECT co.branch_id, co.created_at AS activity_at
                FROM dbo.concession_order co
                  WHERE co.user_id = ? AND co.branch_id IS NOT NULL
            ) AS recent
            ORDER BY activity_at DESC
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long branchId = rs.getLong(1);
                    return rs.wasNull() ? null : branchId;
                }
            }
        }
        return null;
    }

    // ---- point_ledger ----

    /** Ghi sổ giao dịch điểm trong connection của caller (cùng transaction adjustPoints). */
    public void insertLedger(Connection conn, long userId, int delta, int balanceAfter,
                             String refType, Long refId) throws Exception {
        insertLedger(conn, userId, delta, balanceAfter, refType, refId, null, null, null);
    }

    /** Ghi sổ giao dịch điểm với idempotency key để tránh duplicate. */
    public void insertLedger(Connection conn, long userId, int delta, int balanceAfter,
                             String refType, Long refId, String idempotencyKey) throws Exception {
        insertLedger(conn, userId, delta, balanceAfter, refType, refId, idempotencyKey, null, null);
    }

    /** Ghi sổ giao dịch điểm với staff/branch nhưng không có idempotency key. */
    public void insertLedger(Connection conn, long userId, int delta, int balanceAfter,
                             String refType, Long refId, Long staffId, Long branchId) throws Exception {
        insertLedger(conn, userId, delta, balanceAfter, refType, refId, null, staffId, branchId);
    }

    /** Ghi sổ giao dịch điểm với idempotency key + staff/branch để tránh duplicate. */
    public void insertLedger(Connection conn, long userId, int delta, int balanceAfter,
                             String refType, Long refId, String idempotencyKey,
                             Long staffId, Long branchId) throws Exception {
        String sql = """
            INSERT INTO dbo.point_ledger (user_id, delta, balance_after, ref_type, ref_id, idempotency_key, staff_id, branch_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, delta);
            ps.setInt(3, balanceAfter);
            ps.setString(4, refType);
            if (refId == null) ps.setNull(5, java.sql.Types.BIGINT);
            else ps.setLong(5, refId);
            if (idempotencyKey == null) ps.setNull(6, java.sql.Types.VARCHAR);
            else ps.setString(6, idempotencyKey);
            if (staffId == null) ps.setNull(7, java.sql.Types.BIGINT);
            else ps.setLong(7, staffId);
            if (branchId == null) ps.setNull(8, java.sql.Types.BIGINT);
            else ps.setLong(8, branchId);
            ps.executeUpdate();
        }
    }

    /** Lấy ledger entry mới nhất của user (để trả về từ adjust). */
    public Optional<PointLedger> findLatestLedger(Connection conn, long userId) throws Exception {
        String sql = """
            SELECT id, user_id, delta, balance_after, ref_type, ref_id, staff_id, branch_id, created_at
            FROM dbo.point_ledger WHERE user_id = ? ORDER BY created_at DESC, id DESC OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapLedger(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Adjust điểm với idempotency key để tránh duplicate deduction.
     * Nếu đã có ledger với cùng idempotency key → skip.
     *
     * @return số dư sau điều chỉnh
     */
    public int adjustPointsWithIdempotency(Connection conn, long userId, int delta,
                                           boolean allowNegative, String idempotencyKey) throws Exception {
        // Idempotency: VNPay IPN có thể retry → trước khi ghi ledger, check xem
        // idempotencyKey đã được insert chưa. Nếu rồi thì trả về số dư hiện tại
        // mà KHÔNG ghi thêm row (tránh double-charge điểm).
        if (idempotencyKey != null) {
            String checkSql = "SELECT COUNT(*) FROM dbo.point_ledger WHERE idempotency_key = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, idempotencyKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        Optional<Integer> current = findPoints(conn, userId);
                        return current.orElse(0);
                    }
                }
            }
        }

        return adjustPoints(conn, userId, delta, allowNegative);
    }

    /** Lịch sử giao dịch điểm của một khách (Req 17.5), mới nhất trước. */
    public List<PointLedger> findLedger(long userId) throws Exception {
        String sql = """
            SELECT id, user_id, delta, balance_after, ref_type, ref_id, staff_id, branch_id, created_at
            FROM dbo.point_ledger WHERE user_id = ? ORDER BY created_at DESC, id DESC
            """;
        List<PointLedger> ledger = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ledger.add(mapLedger(rs));
                }
            }
        }
        return ledger;
    }

    /** Lịch sử điểm phân trang với giới hạn branch (staff: chỉ khách đã giao dịch ở chi nhánh). */
    public List<PointLedger> findLedgerPaginated(long userId, Long branchId, int limit, int offset) throws Exception {
        String sql = """
            SELECT pl.id, pl.user_id, pl.delta, pl.balance_after, pl.ref_type, pl.ref_id,
                   pl.staff_id, pl.branch_id, pl.created_at
            FROM dbo.point_ledger pl
            WHERE pl.user_id = ?
            """;
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (branchId != null && branchId > 0) {
            sql += " AND pl.branch_id = ?";
            params.add(branchId);
        }
        sql += " ORDER BY pl.created_at DESC, pl.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        params.add(offset);
        params.add(limit);

        List<PointLedger> ledger = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ledger.add(mapLedger(rs));
                }
            }
        }
        return ledger;
    }

    /** Đếm tổng ledger entries cho pagination. */
    public int countLedger(long userId, Long branchId) throws Exception {
        String sql = "SELECT COUNT(*) FROM dbo.point_ledger WHERE user_id = ?";
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (branchId != null && branchId > 0) {
            sql += " AND branch_id = ?";
            params.add(branchId);
        }
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    private PointLedger mapLedger(ResultSet rs) throws Exception {
        PointLedger entry = new PointLedger();
        entry.setId(rs.getLong("id"));
        entry.setUserId(rs.getLong("user_id"));
        entry.setDelta(rs.getInt("delta"));
        entry.setBalanceAfter(rs.getInt("balance_after"));
        entry.setRefType(rs.getString("ref_type"));
        long refId = rs.getLong("ref_id");
        entry.setRefId(rs.wasNull() ? null : refId);
        long staffId = rs.getLong("staff_id");
        entry.setStaffId(rs.wasNull() ? null : staffId);
        long branchId = rs.getLong("branch_id");
        entry.setBranchId(rs.wasNull() ? null : branchId);
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) entry.setCreatedAt(created.toLocalDateTime());
        return entry;
    }

    private LoyaltyTier mapTier(ResultSet rs) throws Exception {
        LoyaltyTier tier = new LoyaltyTier();
        tier.setId(rs.getLong("id"));
        tier.setTierName(rs.getString("tier_name"));
        tier.setMinPoints(rs.getInt("min_points"));
        tier.setDiscountPercent(rs.getInt("discount_percent"));
        tier.setPointRate(rs.getDouble("point_rate"));
        try {
            tier.setRedemptionRate(rs.getLong("redemption_rate"));
        } catch (Exception e) {
            tier.setRedemptionRate(100L); // default
        }
        return tier;
    }
}
