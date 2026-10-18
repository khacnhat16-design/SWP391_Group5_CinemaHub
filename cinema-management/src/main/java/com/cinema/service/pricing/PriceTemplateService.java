package com.cinema.service.pricing;

import com.cinema.dao.pricing.PriceRuleDAO;
import com.cinema.dao.pricing.PriceTemplateDAO;
import com.cinema.model.pricing.PriceRule;
import com.cinema.model.pricing.PriceTemplate;
import com.cinema.model.pricing.PriceTemplateRule;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.common.ServiceException;
import com.cinema.infrastructure.database.DBContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Business rules and transaction boundaries for reusable ticket-price templates. */
public class PriceTemplateService {
    private static final Logger LOGGER = Logger.getLogger(PriceTemplateService.class.getName());
    private static final Set<String> SEAT_TYPES = Set.of("STANDARD", "VIP", "COUPLE");
    private static final Set<String> DAY_TYPES = Set.of("WEEKDAY", "WEEKEND", "HOLIDAY");
    private static final Set<String> TIME_SLOTS = Set.of("EARLY", "DAY", "LATE");
    private static final Set<String> TICKET_TYPES = Set.of("ADULT", "CHILD", "STUDENT", "VIP");

    public record TemplateInput(String name, String description, List<PriceTemplateRule> rules) { }
    public record BranchPreview(long branchId, int templateRuleCount, int existingRuleCount,
                                boolean conflict, String stateToken) { }
    public record ApplyPreview(long movieId, long templateId, List<BranchPreview> branches) { }
    public record BranchApplyResult(long branchId, String status, int appliedRuleCount,
                                    String message) { }
    public record ApplyResult(long movieId, long templateId, List<BranchApplyResult> branches) { }

    private final PriceTemplateDAO templateDao;
    private final PriceRuleDAO priceRuleDao;

    public PriceTemplateService(PriceTemplateDAO templateDao, PriceRuleDAO priceRuleDao) {
        this.templateDao = templateDao;
        this.priceRuleDao = priceRuleDao;
    }

    public List<PriceTemplateDAO.Summary> listTemplates(AccessScope scope) throws Exception {
        requireReader(scope);
        return templateDao.findAll();
    }

    public PriceTemplate getTemplate(AccessScope scope, long templateId) throws Exception {
        requireReader(scope);
        return findTemplate(templateId);
    }

    public List<PriceTemplateRule> previewSource(AccessScope scope, long movieId, long branchId)
            throws Exception {
        requireTemplateManager(scope);
        requireManagerBranch(scope, branchId);
        try (Connection conn = DBContext.getConnection()) {
            requireMovieAndBranch(conn, movieId, branchId);
            List<PriceTemplateRule> rules = fromPriceRules(
                    priceRuleDao.findMovieBranch(conn, movieId, branchId));
            if (rules.isEmpty()) {
                throw new ServiceException.Validation("Phim và chi nhánh nguồn chưa có khung giá để sao chép");
            }
            validateRules(rules, false);
            return rules;
        }
    }

    public PriceTemplate createTemplate(AccessScope scope, TemplateInput input) throws Exception {
        requireTemplateManager(scope);
        validateTemplateInput(input, true);
        PriceTemplate template = new PriceTemplate();
        template.setName(input.name().trim());
        template.setDescription(cleanDescription(input.description()));
        template.setSourceType("MANUAL");
        template.setCreatedBy(scope.userId());
        template.setRules(input.rules());

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long id = templateDao.insertHeader(conn, template);
                templateDao.replaceRules(conn, id, template.getRules());
                PriceTemplate saved = templateDao.findById(conn, id)
                        .orElseThrow(() -> new IllegalStateException("Mẫu giá vừa tạo không đọc lại được"));
                conn.commit();
                return saved;
            } catch (Exception e) {
                rollback(conn, e);
                throw e;
            }
        }
    }

    public PriceTemplate createFromMovie(AccessScope scope, String name, String description,
                                         long movieId, long sourceBranchId) throws Exception {
        requireTemplateManager(scope);
        requireManagerBranch(scope, sourceBranchId);
        String normalizedName = validateName(name);
        String normalizedDescription = cleanDescription(description);
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                requireMovieAndBranch(conn, movieId, sourceBranchId);
                List<PriceTemplateRule> rules = fromPriceRules(
                        priceRuleDao.findMovieBranch(conn, movieId, sourceBranchId));
                if (rules.isEmpty()) {
                    throw new ServiceException.Validation(
                            "Phim và chi nhánh nguồn chưa có khung giá để sao chép");
                }
                validateRules(rules, false);

                PriceTemplate template = new PriceTemplate();
                template.setName(normalizedName);
                template.setDescription(normalizedDescription);
                template.setSourceType("CLONED");
                template.setCreatedBy(scope.userId());
                template.setRules(rules);
                long id = templateDao.insertHeader(conn, template);
                templateDao.replaceRules(conn, id, rules);
                PriceTemplate saved = templateDao.findById(conn, id)
                        .orElseThrow(() -> new IllegalStateException("Mẫu giá vừa tạo không đọc lại được"));
                conn.commit();
                return saved;
            } catch (Exception e) {
                rollback(conn, e);
                throw e;
            }
        }
    }

    public PriceTemplate updateTemplate(AccessScope scope, long templateId, TemplateInput input)
            throws Exception {
        requireTemplateManager(scope);
        requireTemplateOwner(scope, templateId);
        PriceTemplate existing = findTemplate(templateId);
        validateTemplateInput(input, "MANUAL".equals(existing.getSourceType()));
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!templateDao.updateHeader(conn, templateId, input.name().trim(),
                        cleanDescription(input.description()))) {
                    throw new ServiceException.NotFound("Mẫu giá không tồn tại");
                }
                templateDao.replaceRules(conn, templateId, input.rules());
                PriceTemplate updated = templateDao.findById(conn, templateId)
                        .orElseThrow(() -> new ServiceException.NotFound("Mẫu giá không tồn tại"));
                conn.commit();
                return updated;
            } catch (Exception e) {
                rollback(conn, e);
                throw e;
            }
        }
    }

    public void deleteTemplate(AccessScope scope, long templateId) throws Exception {
        requireTemplateManager(scope);
        requireTemplateOwner(scope, templateId);
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!templateDao.delete(conn, templateId)) {
                    throw new ServiceException.NotFound("Mẫu giá không tồn tại");
                }
                conn.commit();
            } catch (Exception e) {
                rollback(conn, e);
                throw e;
            }
        }
    }

    public ApplyPreview previewApplication(AccessScope scope, long templateId, long movieId,
                                           List<Long> branchIds) throws Exception {
        requireReader(scope);
        List<Long> targets = validateTargets(scope, movieId, branchIds);
        PriceTemplate template = findTemplate(templateId);
        if (template.getRules().isEmpty()) {
            throw new ServiceException.Validation("Mẫu giá không có khung giá");
        }
        List<BranchPreview> previews = new ArrayList<>();
        try (Connection conn = DBContext.getConnection()) {
            requireMovieAndBranches(conn, movieId, targets);
            for (long branchId : targets) {
                List<PriceRule> current = priceRuleDao.findMovieBranch(conn, movieId, branchId);
                previews.add(new BranchPreview(branchId, template.getRules().size(), current.size(),
                        !current.isEmpty(), stateToken(current)));
            }
        }
        return new ApplyPreview(movieId, templateId, previews);
    }

    public ApplyResult applyTemplate(AccessScope scope, long templateId, long movieId,
                                     List<Long> branchIds, Map<Long, String> expectedState,
                                     boolean replaceExisting) throws Exception {
        requireReader(scope);
        List<Long> targets = validateTargets(scope, movieId, branchIds);
        PriceTemplate template = findTemplate(templateId);
        if (template.getRules().isEmpty()) {
            throw new ServiceException.Validation("Mẫu giá không có khung giá");
        }
        if (expectedState == null) {
            throw new ServiceException.Validation("Thiếu trạng thái xem trước; hãy xem trước lại");
        }
        try (Connection conn = DBContext.getConnection()) {
            requireMovieAndBranches(conn, movieId, targets);
        }

        List<BranchApplyResult> results = new ArrayList<>();
        for (long branchId : targets) {
            results.add(applyToBranch(movieId, branchId, template.getRules(),
                    expectedState.get(branchId), replaceExisting));
        }
        return new ApplyResult(movieId, templateId, results);
    }

    static void validateManualRules(List<PriceTemplateRule> rules) {
        validateRules(rules, true);
    }

    static void validateClonedRules(List<PriceTemplateRule> rules) {
        validateRules(rules, false);
    }

    private BranchApplyResult applyToBranch(long movieId, long branchId,
                                            List<PriceTemplateRule> templateRules,
                                            String expectedToken, boolean replaceExisting) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return new BranchApplyResult(branchId, "CONFLICT", 0,
                    "Thiếu trạng thái xem trước; vui lòng tải lại bản xem trước.");
        }
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<PriceRule> existing = priceRuleDao.findMovieBranch(conn, movieId, branchId);
                if (!stateToken(existing).equals(expectedToken)) {
                    conn.rollback();
                    return new BranchApplyResult(branchId, "CONFLICT", 0,
                            "Giá tại chi nhánh đã thay đổi sau khi xem trước; vui lòng xem trước lại.");
                }
                if (!existing.isEmpty() && !replaceExisting) {
                    conn.rollback();
                    return new BranchApplyResult(branchId, "CONFLICT", 0,
                            "Chi nhánh đã có giá cho phim; cần xác nhận thay thế.");
                }
                if (!existing.isEmpty()) {
                    priceRuleDao.deleteMovieBranch(conn, movieId, branchId);
                }
                for (PriceTemplateRule templateRule : templateRules) {
                    priceRuleDao.insert(conn, toPriceRule(templateRule, movieId, branchId));
                }
                conn.commit();
                return new BranchApplyResult(branchId, "APPLIED", templateRules.size(),
                        "Đã áp dụng " + templateRules.size() + " khung giá.");
            } catch (Exception e) {
                rollback(conn, e);
                throw e;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Không thể áp dụng mẫu giá cho movieId=" + movieId
                    + ", branchId=" + branchId, e);
            return new BranchApplyResult(branchId, "FAILED", 0,
                    "Không áp dụng được giá tại chi nhánh này. Kiểm tra dữ liệu rồi thử lại.");
        }
    }

    private PriceTemplate findTemplate(long templateId) throws Exception {
        if (templateId < 1) throw new ServiceException.Validation("ID mẫu giá không hợp lệ");
        return templateDao.findById(templateId)
                .orElseThrow(() -> new ServiceException.NotFound("Mẫu giá không tồn tại"));
    }

    private static List<Long> validateTargets(AccessScope scope, long movieId,
                                              List<Long> branchIds) {
        if (movieId < 1) throw new ServiceException.Validation("movieId không hợp lệ");
        if (branchIds == null || branchIds.isEmpty()) {
            throw new ServiceException.Validation("Chọn ít nhất một chi nhánh");
        }
        List<Long> targets = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long branchId : branchIds) {
            if (branchId == null || branchId < 1 || !seen.add(branchId)) {
                throw new ServiceException.Validation("Danh sách chi nhánh không hợp lệ hoặc bị trùng");
            }
            if (scope.role() == Role.BRANCH_MANAGER && !scope.includesBranch(branchId)) {
                throw new ServiceException.Forbidden("Bạn không có quyền áp dụng giá tại chi nhánh này");
            }
            targets.add(branchId);
        }
        return List.copyOf(targets);
    }

    private static void requireMovieAndBranch(Connection conn, long movieId, long branchId)
            throws Exception {
        requireExists(conn, "dbo.movie", movieId, "Phim nguồn không tồn tại");
        requireExists(conn, "dbo.branch", branchId, "Chi nhánh nguồn không tồn tại");
    }

    private static void requireMovieAndBranches(Connection conn, long movieId, List<Long> branchIds)
            throws Exception {
        requireExists(conn, "dbo.movie", movieId, "Phim không tồn tại");
        for (long branchId : branchIds) {
            requireExists(conn, "dbo.branch", branchId, "Chi nhánh không tồn tại: " + branchId);
        }
    }

    private static void requireExists(Connection conn, String table, long id, String message)
            throws Exception {
        String sql = "SELECT 1 FROM " + table + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ServiceException.NotFound(message);
            }
        }
    }

    private static void validateTemplateInput(TemplateInput input, boolean requireBaseMatrix) {
        if (input == null) throw new ServiceException.Validation("Thiếu dữ liệu mẫu giá");
        validateName(input.name());
        cleanDescription(input.description());
        validateRules(input.rules(), requireBaseMatrix);
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ServiceException.Validation("Tên mẫu giá là bắt buộc");
        }
        String normalized = name.trim();
        if (normalized.length() > 120) {
            throw new ServiceException.Validation("Tên mẫu giá tối đa 120 ký tự");
        }
        return normalized;
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) return null;
        String normalized = description.trim();
        if (normalized.length() > 500) {
            throw new ServiceException.Validation("Mô tả tối đa 500 ký tự");
        }
        return normalized;
    }

    private static void validateRules(List<PriceTemplateRule> rules, boolean requireBaseMatrix) {
        if (rules == null || rules.isEmpty()) {
            throw new ServiceException.Validation("Mẫu giá cần có ít nhất một khung giá");
        }
        Set<String> combinations = new HashSet<>();
        Set<String> baseCombinations = new HashSet<>();
        for (int index = 0; index < rules.size(); index++) {
            PriceTemplateRule rule = rules.get(index);
            if (rule == null || !SEAT_TYPES.contains(rule.getSeatType())
                    || !DAY_TYPES.contains(rule.getDayType())
                    || !TIME_SLOTS.contains(rule.getTimeSlot())) {
                throw new ServiceException.Validation("Khung giá thứ " + (index + 1)
                        + " có loại ghế, ngày hoặc khung giờ không hợp lệ");
            }
            if (rule.getPrice() < 0 || (requireBaseMatrix && rule.getPrice() < 1000)) {
                throw new ServiceException.Validation("Giá khung thứ " + (index + 1)
                        + (requireBaseMatrix ? " phải từ 1.000 VND" : " không thể âm"));
            }
            if (rule.getTicketType() != null && !TICKET_TYPES.contains(rule.getTicketType())) {
                throw new ServiceException.Validation("Loại vé ở khung giá thứ " + (index + 1)
                        + " không hợp lệ");
            }
            LocalDate from = rule.getActiveFrom();
            LocalDate to = rule.getActiveTo();
            if ((from == null) != (to == null) || (from != null && to.isBefore(from))) {
                throw new ServiceException.Validation("Ngày hiệu lực ở khung giá thứ "
                        + (index + 1) + " không hợp lệ");
            }
            String key = combinationKey(rule);
            if (!combinations.add(key)) {
                throw new ServiceException.Validation("Khung giá bị trùng tổ hợp ở dòng "
                        + (index + 1));
            }
            if (rule.getTicketType() == null && from == null && to == null) {
                baseCombinations.add(rule.getSeatType() + "|" + rule.getDayType()
                        + "|" + rule.getTimeSlot());
            }
        }
        if (requireBaseMatrix && baseCombinations.size() != 27) {
            throw new ServiceException.Validation(
                    "Mẫu nhập tay cần đủ 27 tổ hợp giá cơ bản không gắn loại vé hoặc ngày hiệu lực");
        }
    }

    private static String combinationKey(PriceTemplateRule rule) {
        return rule.getSeatType() + "|" + rule.getDayType() + "|" + rule.getTimeSlot()
                + "|" + valueOrEmpty(rule.getTicketType()) + "|"
                + (rule.getActiveFrom() == null ? "" : rule.getActiveFrom()) + "|"
                + (rule.getActiveTo() == null ? "" : rule.getActiveTo());
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<PriceTemplateRule> fromPriceRules(List<PriceRule> source) {
        List<PriceTemplateRule> rules = new ArrayList<>();
        for (PriceRule sourceRule : source) {
            PriceTemplateRule rule = new PriceTemplateRule();
            rule.setSeatType(sourceRule.seatType());
            rule.setDayType(sourceRule.dayType());
            rule.setTimeSlot(sourceRule.timeSlot());
            rule.setPrice(sourceRule.price());
            rule.setTicketType(sourceRule.ticketType());
            rule.setActiveFrom(sourceRule.activeFrom());
            rule.setActiveTo(sourceRule.activeTo());
            rules.add(rule);
        }
        return rules;
    }

    private static PriceRule toPriceRule(PriceTemplateRule source, long movieId, long branchId) {
        PriceRule rule = new PriceRule();
        rule.setMovieId(movieId);
        rule.setBranchId(branchId);
        rule.setSeatType(source.getSeatType());
        rule.setDayType(source.getDayType());
        rule.setTimeSlot(source.getTimeSlot());
        rule.setPrice(source.getPrice());
        rule.setTicketType(source.getTicketType());
        rule.setActiveFrom(source.getActiveFrom());
        rule.setActiveTo(source.getActiveTo());
        return rule;
    }

    private static String stateToken(List<PriceRule> rules) throws Exception {
        StringBuilder state = new StringBuilder();
        for (PriceRule rule : rules) {
            state.append(rule.id()).append('|')
                    .append(rule.seatType()).append('|')
                    .append(rule.dayType()).append('|')
                    .append(rule.timeSlot()).append('|')
                    .append(rule.price()).append('|')
                    .append(valueOrEmpty(rule.ticketType())).append('|')
                    .append(rule.activeFrom() == null ? "" : rule.activeFrom()).append('|')
                    .append(rule.activeTo() == null ? "" : rule.activeTo()).append('\n');
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(state.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static void rollback(Connection conn, Exception cause) {
        try {
            conn.rollback();
        } catch (Exception rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void requireReader(AccessScope scope) {
        if (scope == null || (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER)) {
            throw new ServiceException.Forbidden("Chỉ Admin hoặc Quản lý chi nhánh được dùng mẫu giá");
        }
    }

    static void requireTemplateManager(AccessScope scope) {
        if (scope == null
                || (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER)) {
            throw new ServiceException.Forbidden(
                    "Chỉ Admin hoặc Quản lý chi nhánh được quản lý mẫu giá");
        }
    }

    /**
     * BR-WS-02 — Admin sửa/xóa mọi mẫu. Branch Manager chỉ được sửa hoặc xóa mẫu do
     * chính mình tạo; mẫu của người khác (kể cả Manager khác) bị từ chối.
     */
    private void requireTemplateOwner(AccessScope scope, long templateId) throws Exception {
        if (scope == null || scope.role() == Role.ADMIN) return;
        if (scope.role() != Role.BRANCH_MANAGER) {
            throw new ServiceException.Forbidden(
                    "Chỉ Admin hoặc Quản lý chi nhánh được quản lý mẫu giá");
        }
        PriceTemplate template = templateDao.findById(templateId)
                .orElseThrow(() -> new ServiceException.NotFound("Mẫu giá không tồn tại"));
        Long ownerId = template.getCreatedBy();
        if (ownerId == null || scope.userId() == null || !ownerId.equals(scope.userId())) {
            throw new ServiceException.Forbidden(
                    "Bạn chỉ có thể chỉnh sửa hoặc xóa mẫu giá do bạn tạo");
        }
    }

    static void requireManagerBranch(AccessScope scope, long branchId) {
        if (scope.role() == Role.BRANCH_MANAGER && !scope.includesBranch(branchId)) {
            throw new ServiceException.Forbidden("Không có quyền truy cập chi nhánh nguồn này");
        }
    }
}
