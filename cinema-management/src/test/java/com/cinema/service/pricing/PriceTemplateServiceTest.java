package com.cinema.service.pricing;

import com.cinema.dao.pricing.PriceRuleDAO;
import com.cinema.dao.pricing.PriceTemplateDAO;
import com.cinema.model.pricing.PriceTemplateRule;
import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.common.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriceTemplateServiceTest {

    @Test
    void manualTemplateRequiresAllTwentySevenBaseCombinations() {
        assertDoesNotThrow(() -> PriceTemplateService.validateManualRules(baseRules()));

        List<PriceTemplateRule> incomplete = baseRules();
        incomplete.remove(0);
        assertThrows(ServiceException.Validation.class,
                () -> PriceTemplateService.validateManualRules(incomplete));
    }

    @Test
    void manualTemplateRejectsDuplicatesAndInvalidPrices() {
        List<PriceTemplateRule> duplicate = baseRules();
        duplicate.add(copy(duplicate.get(0)));
        assertThrows(ServiceException.Validation.class,
                () -> PriceTemplateService.validateManualRules(duplicate));

        List<PriceTemplateRule> invalidPrice = baseRules();
        invalidPrice.get(0).setPrice(999);
        assertThrows(ServiceException.Validation.class,
                () -> PriceTemplateService.validateManualRules(invalidPrice));
    }

    @Test
    void manualTemplateRejectsIncompleteOrReversedEffectiveDates() {
        List<PriceTemplateRule> incompleteDates = baseRules();
        incompleteDates.get(0).setActiveFrom(LocalDate.of(2026, 1, 1));
        assertThrows(ServiceException.Validation.class,
                () -> PriceTemplateService.validateManualRules(incompleteDates));

        List<PriceTemplateRule> reversedDates = baseRules();
        reversedDates.get(0).setActiveFrom(LocalDate.of(2026, 2, 1));
        reversedDates.get(0).setActiveTo(LocalDate.of(2026, 1, 1));
        assertThrows(ServiceException.Validation.class,
                () -> PriceTemplateService.validateManualRules(reversedDates));
    }

    @Test
    void clonedTemplatePreservesPartialSourceSetsAndZeroPrice() {
        PriceTemplateRule sourceRule = baseRules().get(0);
        sourceRule.setPrice(0);
        sourceRule.setTicketType("CHILD");
        sourceRule.setActiveFrom(LocalDate.of(2026, 3, 1));
        sourceRule.setActiveTo(LocalDate.of(2026, 3, 31));

        assertDoesNotThrow(() -> PriceTemplateService.validateClonedRules(List.of(sourceRule)));
    }

    @Test
    void adminAndBranchManagerCanManageSharedTemplates() {
        AccessScope manager = AccessScope.forRole(Role.BRANCH_MANAGER, Set.of(5L));
        AccessScope admin = AccessScope.forRole(Role.ADMIN, Set.of());
        AccessScope staff = AccessScope.forRole(Role.BRANCH_STAFF, Set.of(5L));

        assertDoesNotThrow(() -> PriceTemplateService.requireTemplateManager(manager));
        assertDoesNotThrow(() -> PriceTemplateService.requireTemplateManager(admin));
        assertThrows(ServiceException.Forbidden.class,
                () -> PriceTemplateService.requireTemplateManager(staff));
        assertDoesNotThrow(() -> PriceTemplateService.requireManagerBranch(manager, 5L));
        assertDoesNotThrow(() -> PriceTemplateService.requireManagerBranch(admin, 99L));
        assertThrows(ServiceException.Forbidden.class,
                () -> PriceTemplateService.requireManagerBranch(manager, 99L));
    }

    @Test
    void branchManagerCannotApplySharedTemplateOutsideAssignedBranches() {
        PriceTemplateService service = new PriceTemplateService(new PriceTemplateDAO(), new PriceRuleDAO());
        AccessScope manager = AccessScope.forRole(Role.BRANCH_MANAGER, Set.of(5L));

        assertThrows(ServiceException.Forbidden.class,
                () -> service.previewApplication(manager, 1, 1, List.of(99L)));
        assertThrows(ServiceException.Forbidden.class,
                () -> service.applyTemplate(manager, 1, 1, List.of(99L), null, true));
    }

    private static List<PriceTemplateRule> baseRules() {
        List<PriceTemplateRule> rules = new ArrayList<>();
        for (String seat : List.of("STANDARD", "VIP", "COUPLE")) {
            for (String day : List.of("WEEKDAY", "WEEKEND", "HOLIDAY")) {
                for (String slot : List.of("EARLY", "DAY", "LATE")) {
                    PriceTemplateRule rule = new PriceTemplateRule();
                    rule.setSeatType(seat);
                    rule.setDayType(day);
                    rule.setTimeSlot(slot);
                    rule.setPrice(5000);
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    private static PriceTemplateRule copy(PriceTemplateRule source) {
        PriceTemplateRule result = new PriceTemplateRule();
        result.setSeatType(source.getSeatType());
        result.setDayType(source.getDayType());
        result.setTimeSlot(source.getTimeSlot());
        result.setPrice(source.getPrice());
        result.setTicketType(source.getTicketType());
        result.setActiveFrom(source.getActiveFrom());
        result.setActiveTo(source.getActiveTo());
        return result;
    }
}
