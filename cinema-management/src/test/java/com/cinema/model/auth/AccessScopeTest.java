package com.cinema.model.auth;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessScopeTest {

    @Test
    void branchManagerHasCinemaOperationsPermissionsWithoutRemovedFeatures() {
        AccessScope scope = AccessScope.forRole(Role.BRANCH_MANAGER, Set.of(1L));

        assertTrue(scope.can("PRICING_MANAGE"));
        assertTrue(scope.can("REPORT_VIEW"));
        assertFalse(scope.can("VOUCHER_MANAGE"));
        assertFalse(scope.can("SHIFT_ASSIGNMENT_MANAGE"));
        assertFalse(scope.can("SHIFT_REQUEST_REVIEW"));
        assertFalse(scope.can("SHIFT_ASSIGNMENT_VIEW_OWN"));
        assertFalse(scope.can("SHIFT_REQUEST_SUBMIT"));
    }

    @Test
    void branchStaffCanSellTicketsButNotAccessRemovedFeatures() {
        AccessScope scope = AccessScope.forRole(Role.BRANCH_STAFF, Set.of(1L));

        assertFalse(scope.can("SHIFT_ASSIGNMENT_VIEW_OWN"));
        assertFalse(scope.can("SHIFT_REQUEST_SUBMIT"));
        assertFalse(scope.can("SHIFT_ASSIGNMENT_MANAGE"));
        assertFalse(scope.can("VOUCHER_MANAGE"));
        assertTrue(scope.can("TICKET_SELL"));
    }
}
