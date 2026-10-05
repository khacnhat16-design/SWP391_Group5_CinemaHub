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
            "LOYALTY_ADJUST",
            // Work Scheduling (REQ-WS)
            "SHIFT_ASSIGNMENT_VIEW_OWN", "SHIFT_REQUEST_SUBMIT");
    private static final Set<String> MANAGER_PERMISSIONS = Set.of(
            "SHOWTIME_BROWSE", "SCREEN_MANAGE", "SHOWTIME_MANAGE", "INVENTORY_MANAGE",
            "SHIFT_APPROVE", "REPORT_VIEW", "REFUND_APPROVE", "TICKET_SELL",
            "CONCESSION_SELL", "LOYALTY_ADJUST", "VOUCHER_MANAGE",
            // Work Scheduling (REQ-WS)
            // Branch Manager được cấu hình ca mẫu cho chi nhánh mình phụ trách
            // (BR-WS-02) — Admin chịu trách nhiệm duyệt thay đổi lớn nhưng
            // ca mẫu là khung giờ nội bộ nên Manager có thể tự tạo/sửa.
            "SHIFT_TEMPLATE_MANAGE",
            "SHIFT_ASSIGNMENT_MANAGE", "SHIFT_REQUEST_REVIEW");
    private static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "SHOWTIME_BROWSE", "BRANCH_MANAGE", "MOVIE_MANAGE", "PRICING_MANAGE",
            "VOUCHER_MANAGE", "HOLIDAY_MANAGE", "LOYALTY_MANAGE", "PRODUCT_MANAGE", "USER_MANAGE",
            "REPORT_VIEW", "AUDIT_VIEW",
            // Work Scheduling (REQ-WS)
            "SHIFT_TEMPLATE_MANAGE", "SHIFT_ASSIGNMENT_MANAGE", "SHIFT_ASSIGNMENT_VIEW_OWN",
            "SHIFT_REQUEST_SUBMIT", "SHIFT_REQUEST_REVIEW");

    private final Role role;
    private final Set<Long> branchIds;
    private final Long userId;

    private AccessScope(Role role, Set<Long> branchIds) {
        this(role, branchIds, null);
    }

    private AccessScope(Role role, Set<Long> branchIds, Long userId) {
        this.role = role;
        this.branchIds = Collections.unmodifiableSet(Set.copyOf(branchIds));
        this.userId = userId;
    }

    public static AccessScope forGuest() {
        return new AccessScope(null, Set.of(), null);
    }

    public static AccessScope forRole(Role role, Set<Long> branchIds) {
        return new AccessScope(role, branchIds, null);
    }

    /** Gắn userId vào scope — dùng từ AuthFilter / controller nội bộ. */
    public static AccessScope forAuthenticatedUser(Role role, Set<Long> branchIds, Long userId) {
        return new AccessScope(role, branchIds, userId);
    }

    public boolean isGuest() {
        return role == null;
    }

    public Role role() {
        return role;
    }

    public Long userId() {
        return userId;
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
