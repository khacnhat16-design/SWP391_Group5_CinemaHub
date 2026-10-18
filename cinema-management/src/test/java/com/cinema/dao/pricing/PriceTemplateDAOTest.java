package com.cinema.dao.pricing;

import com.cinema.dao.pricing.PriceRuleDAO;
import com.cinema.dao.pricing.PriceTemplateDAO;
import com.cinema.model.pricing.PriceRule;
import com.cinema.model.pricing.PriceTemplate;
import com.cinema.model.pricing.PriceTemplateRule;

import com.cinema.infrastructure.database.DBContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pricingTemplate.integration", matches = "true")
class PriceTemplateDAOTest {

    @Test
    void headerAndRulesRoundTripAndCascadeDeleteInOneTransaction() throws Exception {
        PriceTemplateDAO dao = new PriceTemplateDAO();
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PriceTemplate template = new PriceTemplate();
                template.setName("DAO round-trip test");
                template.setDescription("Temporary transaction fixture");
                template.setSourceType("CLONED");
                PriceTemplateRule line = new PriceTemplateRule();
                line.setSeatType("VIP");
                line.setDayType("WEEKEND");
                line.setTimeSlot("LATE");
                line.setPrice(0);
                line.setTicketType("STUDENT");
                line.setActiveFrom(LocalDate.of(2099, 1, 1));
                line.setActiveTo(LocalDate.of(2099, 1, 31));
                template.setRules(List.of(line));

                long id = dao.insertHeader(conn, template);
                dao.replaceRules(conn, id, template.getRules());
                PriceTemplate loaded = dao.findById(conn, id).orElseThrow();

                assertEquals("CLONED", loaded.getSourceType());
                assertEquals(1, loaded.getRules().size());
                PriceTemplateRule loadedRule = loaded.getRules().get(0);
                assertEquals("STUDENT", loadedRule.getTicketType());
                assertEquals(0, loadedRule.getPrice());
                assertEquals(LocalDate.of(2099, 1, 1), loadedRule.getActiveFrom());
                assertEquals(LocalDate.of(2099, 1, 31), loadedRule.getActiveTo());

                assertTrue(dao.delete(conn, id));
                assertFalse(dao.findById(conn, id).isPresent());
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void priceRuleDaoReadsMovieBranchRulesOnTheProvidedConnection() throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long[] target = findPricedMovieBranch(conn);
                List<PriceRule> rules = new PriceRuleDAO()
                        .findMovieBranch(conn, target[0], target[1]);
                assertFalse(rules.isEmpty());
                assertTrue(rules.stream().allMatch(rule -> target[0] == rule.movieId()
                        && target[1] == rule.branchId() && rule.showtimeId() == null));
            } finally {
                conn.rollback();
            }
        }
    }

    private static long[] findPricedMovieBranch(Connection conn) throws Exception {
        String sql = """
                SELECT TOP 1 movie_id, branch_id
                FROM dbo.price_rule
                WHERE movie_id IS NOT NULL AND branch_id IS NOT NULL AND showtime_id IS NULL
                GROUP BY movie_id, branch_id
                ORDER BY COUNT(*) DESC, movie_id, branch_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("No movie/branch rule set in local test DB");
            return new long[] { rs.getLong("movie_id"), rs.getLong("branch_id") };
        }
    }
}
