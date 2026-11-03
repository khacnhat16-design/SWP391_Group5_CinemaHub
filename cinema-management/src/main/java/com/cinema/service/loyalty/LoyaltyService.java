package com.cinema.service.loyalty;

import com.cinema.dao.loyalty.LoyaltyDAO;
import com.cinema.model.loyalty.LoyaltyTier;
import com.cinema.model.loyalty.PointLedger;
import com.cinema.model.pricing.PricingEngine;

import com.cinema.common.ServiceException;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Service tích điểm & hạng thành viên (Req 17.1-17.8).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Cấu hình hạng: tỷ lệ điểm dương, ngưỡng tăng dần, tên hạng không trùng (Req 17.1);
 *       điều chỉnh chỉ áp dụng cho giao dịch sau — điểm đã ghi không hồi tố (Req 17.8).</li>
 *   <li>Cộng điểm theo tỷ lệ hiện hành trên tổng tiền thực trả (sau voucher) (Req 17.2);
 *       ghi ledger trong cùng transaction.</li>
 *   <li>Vượt ngưỡng hạng cao hơn → tự nâng hạng (Req 17.3).</li>
 *   <li>Hủy/hoàn → trừ lại điểm; không đủ điểm trừ → ghi số dư âm theo chính sách (Req 17.4).</li>
 *   <li>Hai giao dịch cộng điểm đồng thời: adjust nguyên tử points = points + delta
 *       trên row lock — tổng cuối chính xác (Req 17.6).</li>
 *   <li>Tier discount áp trước voucher (Req 17.7 — design.md thứ tự base → tier → voucher).</li>
 * </ul>
 */
public class LoyaltyService {
    private static final Logger logger = Logger.getLogger(LoyaltyService.class.getName());
    /** Chính sách Req 17.4: cho phép số dư điểm âm khi trừ vượt (Admin cấu hình — mặc định cho phép). */
    private static final boolean ALLOW_NEGATIVE_POINTS = true;
    /**
     * Staff điều chỉnh tay (STAFF_ADJUST) luôn chặn âm: tránh typo/adversarial làm khách
     * mất điểm vượt số dư mà không có nghiệp vụ rõ ràng (redeem dùng refType REDEEM,
     * refund dùng REFUND — 2 luồng đó đã có policy riêng).
     */
    private static final boolean STAFF_ADJUST_BLOCK_NEGATIVE = true;
    /** Các refType KHÔNG cho phép số dư âm. */
    private static final java.util.Set<String> NEGATIVE_BLOCKED_REF_TYPES =
            java.util.Set.of("STAFF_ADJUST");

    private final LoyaltyDAO loyaltyDao;
    private final TransactionTemplate tx = new TransactionTemplate();

    public LoyaltyService(LoyaltyDAO loyaltyDao) {
        this.loyaltyDao = loyaltyDao;
    }

    /**
     * Admin cấu hình hạng thành viên (Req 17.1): tên không trùng, ngưỡng không trùng,
     * tỷ lệ điểm dương. Config mới chỉ áp cho giao dịch phát sinh sau (Req 17.8) —
     * điểm/tier đã ghi nhận không bị tính lại.
     */
    public LoyaltyTier configureTier(String tierName, int minPoints, int discountPercent,
                                     double pointRate) throws Exception {
        if (tierName == null || tierName.isBlank()) {
            throw new ServiceException.Validation("Tên hạng là bắt buộc");
        }
        if (pointRate <= 0) {
            throw new ServiceException.Validation("Tỷ lệ tích điểm phải là số dương");
        }
        if (discountPercent < 0 || discountPercent > 100) {
            throw new ServiceException.Validation("Phần trăm giảm giá hạng phải trong khoảng 0-100");
        }
        if (minPoints < 0) {
            throw new ServiceException.Validation("Ngưỡng điểm không được âm");
        }
        String normalized = tierName.trim().toUpperCase();
        if (loyaltyDao.findTierByName(normalized).isPresent()) {
            throw new ServiceException.Conflict("Tên hạng thành viên đã tồn tại");
        }
        // Req 17.1 — ngưỡng hạng không trùng nhau
        for (LoyaltyTier existing : loyaltyDao.findAllTiers()) {
            if (existing.minPoints() == minPoints) {
                throw new ServiceException.Conflict("Ngưỡng điểm " + minPoints
                        + " đã được dùng cho hạng " + existing.tierName());
            }
        }

        LoyaltyTier tier = new LoyaltyTier();
        tier.setTierName(normalized);
        tier.setMinPoints(minPoints);
        tier.setDiscountPercent(discountPercent);
        tier.setPointRate(pointRate);
        loyaltyDao.insertTier(tier);

        logger.info("Configured loyalty tier " + normalized + " (min " + minPoints
                + " pts, discount " + discountPercent + "%, rate " + pointRate + ")");
        return tier;
    }

    public List<LoyaltyTier> listTiers() throws Exception {
        return loyaltyDao.findAllTiers();
    }

    /**
     * Cộng điểm cho giao dịch confirmed đã thanh toán (Req 17.2, 17.3, 17.6).
     * Gọi trong transaction của caller (booking/concession confirm) để điểm và
     * trạng thái đơn cùng nguyên tử.
     *
     * @param amountPaid tổng tiền thực trả sau voucher (đồng)
     * @return số điểm đã cộng
     */
    public int addPointsForPurchase(Connection conn, long userId, long amountPaid,
                                    String refType, Long refId) throws Exception {
        double rate = currentPointRate();
        int points = (int) Math.floor(amountPaid * rate);
        if (points > 0) {
            adjust(conn, userId, points, refType, refId);
        }
        return points;
    }

    /**
     * Trừ lại điểm của giao dịch bị hủy/hoàn (Req 17.4). Không đủ điểm → ghi âm
     * theo chính sách; caller hiển thị cảnh báo cho khách.
     */
    public void deductPointsForRefund(Connection conn, long userId, int pointsDeducted,
                                      String refType, Long refId) throws Exception {
        if (pointsDeducted <= 0) return;
        adjust(conn, userId, -pointsDeducted, refType, refId);
    }

    // ================================================================
    // Phase 3: Points Redemption for F&B Checkout
    // ================================================================

    /**
     * Lấy tỷ lệ đổi điểm — số VND được giảm cho mỗi điểm.
     * Đọc từ loyalty_config (SYSTEM tier hoặc tier cao nhất), mặc định 100 VND/điểm.
     *
     * @return số VND giảm cho mỗi điểm
     */
    public int getRedemptionRate() throws Exception {
        // Loyalty tier SYSTEM là default rate áp dụng cho mọi khách; tier cao
        // hơn (GOLD/PLATINUM) có rate ưu đãi hơn sẽ được lookup ở nơi khác.
        String sql = """
            SELECT TOP 1 redemption_rate
            FROM dbo.loyalty_config
            WHERE tier_name = 'SYSTEM' AND redemption_rate > 0
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("redemption_rate");
                }
            }
        }

        // Fallback: lấy từ bất kỳ tier nào có redemption_rate > 0
        String fallbackSql = """
            SELECT TOP 1 redemption_rate
            FROM dbo.loyalty_config
            WHERE redemption_rate > 0
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(fallbackSql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("redemption_rate");
                }
            }
        }

        // Default: 100 VND per point
        return 100;
    }

    /**
     * Số điểm tối đa khách có thể dùng, giới hạn theo số dư hiện tại chứ không theo giá trị đơn.
     *
     * @param userId ID khách hàng
     * @param orderTotal không dùng; giữ tham số để tương thích các caller hiện tại
     * @return số điểm tối đa có thể đổi
     */
    public int calculateMaxRedeemablePoints(long userId, long orderTotal) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return calculateMaxRedeemablePoints(conn, userId, orderTotal);
        }
    }

    public int calculateMaxRedeemablePoints(Connection conn, long userId, long orderTotal) throws Exception {
        int balance = loyaltyDao.findPoints(conn, userId).orElse(0);
        return Math.max(0, balance);
    }

    /**
     * Tính số tiền được giảm từ điểm đổi.
     *
     * @param points số điểm muốn đổi
     * @param rate tỷ lệ đổi (VND/điểm)
     * @return số tiền được giảm (VND)
     */
    public int calculateDiscount(int points, int rate) {
        if (points <= 0 || rate <= 0) return 0;
        return points * rate;
    }

    /**
     * Trừ điểm khi khách đổi điểm lấy ưu đãi (Phase 3).
     * Dùng trong transaction thanh toán thành công của F&B.
     *
     * @param conn connection đã có transaction
     * @param userId ID khách hàng
     * @param pointsUsed số điểm đã dùng
     * @param refType loại giao dịch (e.g., "FNB_ORDER")
     * @param refId ID đơn hàng liên quan
     */
    public void redeemPoints(Connection conn, long userId, int pointsUsed,
                             String refType, Long refId) throws Exception {
        redeemPoints(conn, userId, pointsUsed, refType, refId,
                refType + "-" + refId + "-REDEEM");
    }

    public void redeemPoints(Connection conn, long userId, int pointsUsed,
                             String refType, Long refId, String idempotencyKey) throws Exception {
        if (pointsUsed <= 0) return;

        int balanceAfter = loyaltyDao.adjustPointsWithIdempotency(
                conn, userId, -pointsUsed, false, idempotencyKey);

        if (balanceAfter < 0) {
            throw new ServiceException.BusinessRule("POINTS_INSUFFICIENT",
                    "Số dư điểm không đủ để đổi (" + pointsUsed + " điểm)");
        }

        // Ledger insert với idempotencyKey (do caller truyền vào hoặc UUID sinh tự
        // động) để IPN callback retry vẫn không ghi 2 dòng trừ điểm trùng nhau.
        loyaltyDao.insertLedger(conn, userId, -pointsUsed, balanceAfter, refType, refId, idempotencyKey, null, null);

        logger.info("Redeemed " + pointsUsed + " points for user " + userId
                + " (refType=" + refType + ", refId=" + refId + ")");
    }

    /**
     * Hoàn điểm khi đơn hàng bị hủy hoặc refund (Phase 3).
     *
     * @param conn connection đã có transaction
     * @param userId ID khách hàng
     * @param pointsToRefund số điểm cần hoàn
     * @param refType loại giao dịch
     * @param refId ID đơn hàng liên quan
     */
    public void refundPoints(Connection conn, long userId, int pointsToRefund,
                             String refType, Long refId) throws Exception {
        refundPoints(conn, userId, pointsToRefund, refType, refId,
                refType + "-" + refId + "-REFUND");
    }

    public void refundPoints(Connection conn, long userId, int pointsToRefund,
                             String refType, Long refId, String idempotencyKey) throws Exception {
        if (pointsToRefund <= 0) return;

        int balanceAfter = loyaltyDao.adjustPointsWithIdempotency(
                conn, userId, pointsToRefund, true, idempotencyKey);

        loyaltyDao.insertLedger(conn, userId, pointsToRefund, balanceAfter, refType, refId, idempotencyKey, null, null);

        logger.info("Refunded " + pointsToRefund + " points to user " + userId
                + " (refType=" + refType + ", refId=" + refId + ")");
    }

    /**
     * Lấy số dư điểm hiện tại của khách.
     *
     * @param userId ID khách hàng
     * @return số dư điểm
     */
    public int findPoints(long userId) throws Exception {
        return loyaltyDao.findPoints(userId).orElse(0);
    }

    /**
     * Kiểm tra khách hàng có đủ điểm để đổi không.
     *
     * @param userId ID khách hàng
     * @param pointsRequired số điểm cần thiết
     * @return true nếu đủ điểm
     */
    public boolean hasEnoughPoints(long userId, int pointsRequired) throws Exception {
        int balance = findPoints(userId);
        return balance >= pointsRequired;
    }

    /**
     * Lấy thông tin loyalty của khách hàng cho frontend (Phase 3).
     * Bao gồm: điểm, hạng, tỷ lệ đổi, điểm tối đa có thể đổi cho một đơn.
     *
     * @param userId ID khách hàng
     * @param orderTotal tổng tiền đơn hàng (để tính điểm tối đa)
     * @return LoyaltyInfo chứa thông tin điểm
     */
    public LoyaltyInfo getLoyaltyInfo(long userId, long orderTotal) throws Exception {
        int balance = findPoints(userId);
        String tier = loyaltyDao.findTier(userId).orElse("STANDARD");
        int rate = getRedemptionRate();
        int maxRedeemable = calculateMaxRedeemablePoints(userId, orderTotal);

        return new LoyaltyInfo(balance, tier, rate, maxRedeemable);
    }

    /** Thông tin loyalty cho frontend (Phase 3). */
    public record LoyaltyInfo(
            int points,            // Số dư điểm hiện tại
            String tier,           // Hạng thành viên
            int redemptionRate,    // VND per point
            int maxRedeemable      // Điểm tối đa có thể đổi
    ) {}

    /**
     * Adjust nguyên tử + ledger + nâng hạng (Req 17.3, 17.6).
     * Hai giao dịch đồng thời serialize trên row lock customer_profile.
     *
     * @param conn      database connection (for transaction boundary control)
     * @param userId    customer user id
     * @param delta     positive (earn) or negative (redeem/deduct) adjustment
     * @param refType   EARN, REDEEM, STAFF_ADJUST, REFUND, EXPIRE
     * @param refId     optional reference to the originating transaction (ticket/order/payment id)
     * @param staffId   nullable — staff who initiated the adjustment (for audit)
     * @param branchId  nullable — branch where the adjustment occurred (for audit)
     * @return PointLedger entry with new balance
     */
    public PointLedger adjust(Connection conn, long userId, int delta, String refType,
                              Long refId, Long staffId, Long branchId) throws Exception {
        boolean allowNegative = ALLOW_NEGATIVE_POINTS
                && !NEGATIVE_BLOCKED_REF_TYPES.contains(refType);
        int balanceAfter = loyaltyDao.adjustPoints(conn, userId, delta, allowNegative);
        if (balanceAfter < 0 && !allowNegative) {
            throw new ServiceException.BusinessRule("POINTS_INSUFFICIENT",
                    "Số dư điểm không đủ để trừ (" + Math.abs(delta) + " điểm)");
        }
        if (balanceAfter < 0) {
            // adjustPoints trả -1 khi không tìm thấy profile — phân biệt với số dư âm thật
            boolean exists = loyaltyDao.findPoints(conn, userId).isPresent();
            if (!exists) {
                throw new ServiceException.NotFound("Hồ sơ khách hàng không tồn tại");
            }
        }
        loyaltyDao.insertLedger(conn, userId, delta, balanceAfter, refType, refId, staffId, branchId);

        // Req 17.3 — nâng hạng khi vượt ngưỡng (đọc config mới nhất, không hồi tố)
        String newTier = resolveTier(conn, balanceAfter);
        String currentTier = loyaltyDao.findTier(conn, userId).orElse(null);
        if (newTier != null && !newTier.equalsIgnoreCase(currentTier)) {
            loyaltyDao.updateTier(conn, userId, newTier);
            logger.info("Upgraded user " + userId + " to tier " + newTier
                    + " (balance " + balanceAfter + ")");
        }
        return loyaltyDao.findLatestLedger(conn, userId).orElse(null);
    }

    /** Convenience overload without staff/branch context (for internal calls). */
    public int adjust(Connection conn, long userId, int delta, String refType, Long refId) throws Exception {
        PointLedger entry = adjust(conn, userId, delta, refType, refId, null, null);
        return entry != null ? entry.balanceAfter() : 0;
    }

    /** Convenience overload with staff/branch but no refId. */
    public PointLedger adjust(Connection conn, long userId, int delta, String refType,
                              Long staffId, Long branchId) throws Exception {
        return adjust(conn, userId, delta, refType, null, staffId, branchId);
    }

    /**
     * Tier discount cho PricingEngine (Req 17.7 — áp TRƯỚC voucher):
     * giảm theo discount_percent của hạng hiện tại trên giá gốc.
     *
     * @return số tiền giảm (đồng)
     */
    public long tierDiscountFor(Long userId, long baseAmount) throws Exception {
        if (userId == null || baseAmount <= 0) return 0;
        String tierName = loyaltyDao.findTier(userId).orElse(null);
        if (tierName == null) return 0;
        LoyaltyTier tier = loyaltyDao.findTierByName(tierName).orElse(null);
        if (tier == null || tier.discountPercent() <= 0) return 0;
        return baseAmount * tier.discountPercent() / 100;
    }

    /** Thông tin trang tài khoản (Req 17.5): điểm, hạng, điểm cần thêm để lên hạng kế. */
    public LoyaltySummary summary(long userId) throws Exception {
        int points = loyaltyDao.findPoints(userId)
                .orElseThrow(() -> new ServiceException.NotFound("Hồ sơ khách hàng không tồn tại"));
        String tier = loyaltyDao.findTier(userId).orElse("STANDARD");
        Integer nextThreshold = nextTierThreshold(points);
        List<PointLedger> ledger = loyaltyDao.findLedger(userId);
        return new LoyaltySummary(points, tier,
                nextThreshold != null ? nextThreshold - points : null, ledger);
    }

    /** Tỷ lệ điểm hiện hành: lấy theo tier cao nhất có cấu hình rate, mặc định 1%. */
    private double currentPointRate() throws Exception {
        return loyaltyDao.findAllTiers().stream()
                .max(Comparator.comparingInt(LoyaltyTier::minPoints))
                .map(LoyaltyTier::pointRate)
                .filter(rate -> rate > 0)
                .orElse(0.01);
    }

    /** Hạng phù hợp với số dư điểm: tier có min_points cao nhất ≤ points. */
    private String resolveTier(Connection conn, int points) throws Exception {
        return loyaltyDao.findAllTiers(conn).stream()
                .filter(tier -> tier.minPoints() <= points)
                .max(Comparator.comparingInt(LoyaltyTier::minPoints))
                .map(LoyaltyTier::tierName)
                .orElse(null);
    }

    /** Ngưỡng hạng kế tiếp trên mức điểm hiện tại (Req 17.5). */
    private Integer nextTierThreshold(int points) throws Exception {
        return loyaltyDao.findAllTiers().stream()
                .map(LoyaltyTier::minPoints)
                .filter(min -> min > points)
                .min(Integer::compareTo)
                .orElse(null);
    }

    /** Dữ liệu hiển thị trang tài khoản (Req 17.5). */
    public record LoyaltySummary(int points, String tier, Integer pointsToNextTier,
                                 List<PointLedger> ledger) { }
}
