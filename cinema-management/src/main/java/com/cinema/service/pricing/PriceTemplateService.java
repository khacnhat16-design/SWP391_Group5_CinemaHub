package com.cinema.service.pricing;

import com.cinema.model.pricing.*;
import com.cinema.dao.pricing.*;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.model.showtime.Showtime;
import com.cinema.model.branch.Branch;
import com.cinema.model.auth.AccessScope;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.common.ServiceException;
// import dal.DBContext;

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
        requireAdmin(scope);
        try (Connection conn = DBContext.getConnection()) {
            requireMovieAndBranch(conn, movieId, branchId);
            List<PriceTemplateRule> rules = fromPriceRules(
                    priceRuleDao.findMovieBranch(conn, movieId, branchId));
            if (rules.isEmpty()) {
                throw new ServiceException.Validation("Phim vÃƒÂ  chi nhÃƒÂ¡nh nguÃ¡Â»â€œn chÃ†Â°a cÃƒÂ³ khung giÃƒÂ¡ Ã„â€˜Ã¡Â»Æ’ sao chÃƒÂ©p");
            }
            validateRules(rules, false);
            return rules;
        }
    }

    public PriceTemplate createTemplate(AccessScope scope, TemplateInput input) throws Exception {
        requireAdmin(scope);
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
                        .orElseThrow(() -> new IllegalStateException("MÃ¡ÂºÂ«u giÃƒÂ¡ vÃ¡Â»Â«a tÃ¡ÂºÂ¡o khÃƒÂ´ng Ã„â€˜Ã¡Â»Âc lÃ¡ÂºÂ¡i Ã„â€˜Ã†Â°Ã¡Â»Â£c"));
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
        requireAdmin(scope);
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
                            "Phim vÃƒÂ  chi nhÃƒÂ¡nh nguÃ¡Â»â€œn chÃ†Â°a cÃƒÂ³ khung giÃƒÂ¡ Ã„â€˜Ã¡Â»Æ’ sao chÃƒÂ©p");
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
                        .orElseThrow(() -> new IllegalStateException("MÃ¡ÂºÂ«u giÃƒÂ¡ vÃ¡Â»Â«a tÃ¡ÂºÂ¡o khÃƒÂ´ng Ã„â€˜Ã¡Â»Âc lÃ¡ÂºÂ¡i Ã„â€˜Ã†Â°Ã¡Â»Â£c"));
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
        requireAdmin(scope);
        PriceTemplate existing = findTemplate(templateId);
        validateTemplateInput(input, "MANUAL".equals(existing.getSourceType()));
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!templateDao.updateHeader(conn, templateId, input.name().trim(),
                        cleanDescription(input.description()))) {
                    throw new ServiceException.NotFound("MÃ¡ÂºÂ«u giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i");
                }
                templateDao.replaceRules(conn, templateId, input.rules());
                PriceTemplate updated = templateDao.findById(conn, templateId)
                        .orElseThrow(() -> new ServiceException.NotFound("MÃ¡ÂºÂ«u giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
                conn.commit();
                return updated;
            } catch (Exception e) {
                rollback(conn, e);
                throw e;
            }
        }
    }

    public void deleteTemplate(AccessScope scope, long templateId) throws Exception {
        requireAdmin(scope);
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!templateDao.delete(conn, templateId)) {
                    throw new ServiceException.NotFound("MÃ¡ÂºÂ«u giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i");
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
            throw new ServiceException.Validation("MÃ¡ÂºÂ«u giÃƒÂ¡ khÃƒÂ´ng cÃƒÂ³ khung giÃƒÂ¡");
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
            throw new ServiceException.Validation("MÃ¡ÂºÂ«u giÃƒÂ¡ khÃƒÂ´ng cÃƒÂ³ khung giÃƒÂ¡");
        }
        if (expectedState == null) {
            throw new ServiceException.Validation("ThiÃ¡ÂºÂ¿u trÃ¡ÂºÂ¡ng thÃƒÂ¡i xem trÃ†Â°Ã¡Â»â€ºc; hÃƒÂ£y xem trÃ†Â°Ã¡Â»â€ºc lÃ¡ÂºÂ¡i");
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
                    "ThiÃ¡ÂºÂ¿u trÃ¡ÂºÂ¡ng thÃƒÂ¡i xem trÃ†Â°Ã¡Â»â€ºc; vui lÃƒÂ²ng tÃ¡ÂºÂ£i lÃ¡ÂºÂ¡i bÃ¡ÂºÂ£n xem trÃ†Â°Ã¡Â»â€ºc.");
        }
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<PriceRule> existing = priceRuleDao.findMovieBranch(conn, movieId, branchId);
                if (!stateToken(existing).equals(expectedToken)) {
                    conn.rollback();
                    return new BranchApplyResult(branchId, "CONFLICT", 0,
                            "GiÃƒÂ¡ tÃ¡ÂºÂ¡i chi nhÃƒÂ¡nh Ã„â€˜ÃƒÂ£ thay Ã„â€˜Ã¡Â»â€¢i sau khi xem trÃ†Â°Ã¡Â»â€ºc; vui lÃƒÂ²ng xem trÃ†Â°Ã¡Â»â€ºc lÃ¡ÂºÂ¡i.");
                }
                if (!existing.isEmpty() && !replaceExisting) {
                    conn.rollback();
                    return new BranchApplyResult(branchId, "CONFLICT", 0,
                            "Chi nhÃƒÂ¡nh Ã„â€˜ÃƒÂ£ cÃƒÂ³ giÃƒÂ¡ cho phim; cÃ¡ÂºÂ§n xÃƒÂ¡c nhÃ¡ÂºÂ­n thay thÃ¡ÂºÂ¿.");
                }
                if (!existing.isEmpty()) {
                    priceRuleDao.deleteMovieBranch(conn, movieId, branchId);
                }
                for (PriceTemplateRule templateRule : templateRules) {
                    priceRuleDao.insert(conn, toPriceRule(templateRule, movieId, branchId));
                }
                conn.commit();
                return new BranchApplyResult(branchId, "APPLIED", templateRules.size(),
                        "Ã„ÂÃƒÂ£ ÃƒÂ¡p dÃ¡Â»Â¥ng " + templateRules.size() + " khung giÃƒÂ¡.");
            } catch (Exception e) {
                rollback(conn, e);
                throw e;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "KhÃƒÂ´ng thÃ¡Â»Æ’ ÃƒÂ¡p dÃ¡Â»Â¥ng mÃ¡ÂºÂ«u giÃƒÂ¡ cho movieId=" + movieId
                    + ", branchId=" + branchId, e);
            return new BranchApplyResult(branchId, "FAILED", 0,
                    "KhÃƒÂ´ng ÃƒÂ¡p dÃ¡Â»Â¥ng Ã„â€˜Ã†Â°Ã¡Â»Â£c giÃƒÂ¡ tÃ¡ÂºÂ¡i chi nhÃƒÂ¡nh nÃƒÂ y. KiÃ¡Â»Æ’m tra dÃ¡Â»Â¯ liÃ¡Â»â€¡u rÃ¡Â»â€œi thÃ¡Â»Â­ lÃ¡ÂºÂ¡i.");
        }
    }

    private PriceTemplate findTemplate(long templateId) throws Exception {
        if (templateId < 1) throw new ServiceException.Validation("ID mÃ¡ÂºÂ«u giÃƒÂ¡ khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡");
        return templateDao.findById(templateId)
                .orElseThrow(() -> new ServiceException.NotFound("MÃ¡ÂºÂ«u giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
    }

    private static List<Long> validateTargets(AccessScope scope, long movieId,
                                              List<Long> branchIds) {
        if (movieId < 1) throw new ServiceException.Validation("movieId khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡");
        if (branchIds == null || branchIds.isEmpty()) {
            throw new ServiceException.Validation("ChÃ¡Â»Ân ÃƒÂ­t nhÃ¡ÂºÂ¥t mÃ¡Â»â„¢t chi nhÃƒÂ¡nh");
        }
        List<Long> targets = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long branchId : branchIds) {
            if (branchId == null || branchId < 1 || !seen.add(branchId)) {
                throw new ServiceException.Validation("Danh sÃƒÂ¡ch chi nhÃƒÂ¡nh khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡ hoÃ¡ÂºÂ·c bÃ¡Â»â€¹ trÃƒÂ¹ng");
            }
            if (scope.role() == Role.BRANCH_MANAGER && !scope.includesBranch(branchId)) {
                throw new ServiceException.Forbidden("BÃ¡ÂºÂ¡n khÃƒÂ´ng cÃƒÂ³ quyÃ¡Â»Ân ÃƒÂ¡p dÃ¡Â»Â¥ng giÃƒÂ¡ tÃ¡ÂºÂ¡i chi nhÃƒÂ¡nh nÃƒÂ y");
            }
            targets.add(branchId);
        }
        return List.copyOf(targets);
    }

    private static void requireMovieAndBranch(Connection conn, long movieId, long branchId)
            throws Exception {
        requireExists(conn, "dbo.movie", movieId, "Phim nguÃ¡Â»â€œn khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i");
        requireExists(conn, "dbo.branch", branchId, "Chi nhÃƒÂ¡nh nguÃ¡Â»â€œn khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i");
    }

    private static void requireMovieAndBranches(Connection conn, long movieId, List<Long> branchIds)
            throws Exception {
        requireExists(conn, "dbo.movie", movieId, "Phim khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i");
        for (long branchId : branchIds) {
            requireExists(conn, "dbo.branch", branchId, "Chi nhÃƒÂ¡nh khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i: " + branchId);
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
        if (input == null) throw new ServiceException.Validation("ThiÃ¡ÂºÂ¿u dÃ¡Â»Â¯ liÃ¡Â»â€¡u mÃ¡ÂºÂ«u giÃƒÂ¡");
        validateName(input.name());
        cleanDescription(input.description());
        validateRules(input.rules(), requireBaseMatrix);
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ServiceException.Validation("TÃƒÂªn mÃ¡ÂºÂ«u giÃƒÂ¡ lÃƒÂ  bÃ¡ÂºÂ¯t buÃ¡Â»â„¢c");
        }
        String normalized = name.trim();
        if (normalized.length() > 120) {
            throw new ServiceException.Validation("TÃƒÂªn mÃ¡ÂºÂ«u giÃƒÂ¡ tÃ¡Â»â€˜i Ã„â€˜a 120 kÃƒÂ½ tÃ¡Â»Â±");
        }
        return normalized;
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) return null;
        String normalized = description.trim();
        if (normalized.length() > 500) {
            throw new ServiceException.Validation("MÃƒÂ´ tÃ¡ÂºÂ£ tÃ¡Â»â€˜i Ã„â€˜a 500 kÃƒÂ½ tÃ¡Â»Â±");
        }
        return normalized;
    }

    private static void validateRules(List<PriceTemplateRule> rules, boolean requireBaseMatrix) {
        if (rules == null || rules.isEmpty()) {
            throw new ServiceException.Validation("MÃ¡ÂºÂ«u giÃƒÂ¡ cÃ¡ÂºÂ§n cÃƒÂ³ ÃƒÂ­t nhÃ¡ÂºÂ¥t mÃ¡Â»â„¢t khung giÃƒÂ¡");
        }
        Set<String> combinations = new HashSet<>();
        Set<String> baseCombinations = new HashSet<>();
        for (int index = 0; index < rules.size(); index++) {
            PriceTemplateRule rule = rules.get(index);
            if (rule == null || !SEAT_TYPES.contains(rule.getSeatType())
                    || !DAY_TYPES.contains(rule.getDayType())
                    || !TIME_SLOTS.contains(rule.getTimeSlot())) {
                throw new ServiceException.Validation("Khung giÃƒÂ¡ thÃ¡Â»Â© " + (index + 1)
                        + " cÃƒÂ³ loÃ¡ÂºÂ¡i ghÃ¡ÂºÂ¿, ngÃƒÂ y hoÃ¡ÂºÂ·c khung giÃ¡Â»Â khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡");
            }
            if (rule.getPrice() < 0 || (requireBaseMatrix && rule.getPrice() < 1000)) {
                throw new ServiceException.Validation("GiÃƒÂ¡ khung thÃ¡Â»Â© " + (index + 1)
                        + (requireBaseMatrix ? " phÃ¡ÂºÂ£i tÃ¡Â»Â« 1.000 VND" : " khÃƒÂ´ng thÃ¡Â»Æ’ ÃƒÂ¢m"));
            }
            if (rule.getTicketType() != null && !TICKET_TYPES.contains(rule.getTicketType())) {
                throw new ServiceException.Validation("LoÃ¡ÂºÂ¡i vÃƒÂ© Ã¡Â»Å¸ khung giÃƒÂ¡ thÃ¡Â»Â© " + (index + 1)
                        + " khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡");
            }
            LocalDate from = rule.getActiveFrom();
            LocalDate to = rule.getActiveTo();
            if ((from == null) != (to == null) || (from != null && to.isBefore(from))) {
                throw new ServiceException.Validation("NgÃƒÂ y hiÃ¡Â»â€¡u lÃ¡Â»Â±c Ã¡Â»Å¸ khung giÃƒÂ¡ thÃ¡Â»Â© "
                        + (index + 1) + " khÃƒÂ´ng hÃ¡Â»Â£p lÃ¡Â»â€¡");
            }
            String key = combinationKey(rule);
            if (!combinations.add(key)) {
                throw new ServiceException.Validation("Khung giÃƒÂ¡ bÃ¡Â»â€¹ trÃƒÂ¹ng tÃ¡Â»â€¢ hÃ¡Â»Â£p Ã¡Â»Å¸ dÃƒÂ²ng "
                        + (index + 1));
            }
            if (rule.getTicketType() == null && from == null && to == null) {
                baseCombinations.add(rule.getSeatType() + "|" + rule.getDayType()
                        + "|" + rule.getTimeSlot());
            }
        }
        if (requireBaseMatrix && baseCombinations.size() != 27) {
            throw new ServiceException.Validation(
                    "MÃ¡ÂºÂ«u nhÃ¡ÂºÂ­p tay cÃ¡ÂºÂ§n Ã„â€˜Ã¡Â»Â§ 27 tÃ¡Â»â€¢ hÃ¡Â»Â£p giÃƒÂ¡ cÃ†Â¡ bÃ¡ÂºÂ£n khÃƒÂ´ng gÃ¡ÂºÂ¯n loÃ¡ÂºÂ¡i vÃƒÂ© hoÃ¡ÂºÂ·c ngÃƒÂ y hiÃ¡Â»â€¡u lÃ¡Â»Â±c");
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
            throw new ServiceException.Forbidden("ChÃ¡Â»â€° Admin hoÃ¡ÂºÂ·c QuÃ¡ÂºÂ£n lÃƒÂ½ chi nhÃƒÂ¡nh Ã„â€˜Ã†Â°Ã¡Â»Â£c dÃƒÂ¹ng mÃ¡ÂºÂ«u giÃƒÂ¡");
        }
    }

    private static void requireAdmin(AccessScope scope) {
        if (scope == null || scope.role() != Role.ADMIN) {
            throw new ServiceException.Forbidden("ChÃ¡Â»â€° Admin mÃ¡Â»â€ºi Ã„â€˜Ã†Â°Ã¡Â»Â£c quÃ¡ÂºÂ£n lÃƒÂ½ mÃ¡ÂºÂ«u giÃƒÂ¡");
        }
    }
}
