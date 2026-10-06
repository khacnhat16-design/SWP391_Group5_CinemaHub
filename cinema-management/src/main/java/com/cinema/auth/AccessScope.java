package com.cinema.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable authorization scope resolved from the authenticated account. */
public final class AccessScope {
    private static final Set<String> PUBLIC_PERMISSIONS = Set.of("SHOWTIME_BROWSE");
    private static final Set<String> CUSTOMER_PERMISSIONS = Set.of(
            "SHOWTIME_BROWSE", "BOOKING_CREATE", "BOOKING_VIEW_OWN", "BOOKING_CANCEL_OWN",
            "CONCESSION_PREORDER", "WALLET_USE", "LOYALTY_VIEW");
    private static final Set<String> STAFF_PERMISSIONS = Set.of(
            "SHOWTIME_BROWSE", "TICKET_SELL", "TICKET_VALIDATE", "CONCESSION_SELL",
            "CONCESSION_PICKUP", "SHIFT_OPEN", "SHIFT_CLOSE", "INVENTORY_VIEW",
            "LOYALTY_ADJUST");
    private static final Set<String> MANAGER_PERMISSIONS = Set.of(
            "SHOWTIME_BROWSE", "SCREEN_MANAGE", "SHOWTIME_MANAGE", "INVENTORY_MANAGE",
            "SHIFT_APPROVE", "REPORT_VIEW", "REFUND_APPROVE", "TICKET_SELL", "TICKET_VALIDATE",
            "CONCESSION_SELL", "LOYALTY_ADJUST");
    private static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "SHOWTIME_BROWSE", "BRANCH_MANAGE", "MOVIE_MANAGE", "PRICING_MANAGE",
            "VOUCHER_MANAGE", "LOYALTY_MANAGE", "PRODUCT_MANAGE", "USER_MANAGE",
            "REPORT_VIEW", "AUDIT_VIEW");

    private final Role role;
    private final Set<Long> branchIds;

    private AccessScope(Role role, Set<Long> branchIds) {
        this.role = role;
        this.branchIds = Collections.unmodifiableSet(Set.copyOf(branchIds));
    }

    public static AccessScope forGuest() {
        return new AccessScope(null, Set.of());
    }

    public static AccessScope forRole(Role role, Set<Long> branchIds) {
        return new AccessScope(role, branchIds);
    }

    public boolean isGuest() {
        return role == null;
    }

    public Role role() {
        return role;
    }

    public Set<Long> branchIds() {
        return branchIds;
    }

    public boolean includesBranch(long branchId) {
        return role == Role.ADMIN || branchIds.contains(branchId);
    }

    public boolean can(String permission) {
        if (isGuest()) {
            return PUBLIC_PERMISSIONS.contains(permission);
        }
        if (role == Role.ADMIN) {
            return true;
        }
        return permissionsFor(role).contains(permission);
    }

    /** Check if this scope can adjust customer loyalty points. */
    public boolean canAdjustPoints() {
        return can("LOYALTY_ADJUST");
    }

    private static Set<String> permissionsFor(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN_PERMISSIONS;
            case BRANCH_MANAGER -> MANAGER_PERMISSIONS;
            case BRANCH_STAFF -> STAFF_PERMISSIONS;
            case CUSTOMER -> CUSTOMER_PERMISSIONS;
        };
    }
}
