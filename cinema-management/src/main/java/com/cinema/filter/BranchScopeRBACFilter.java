package com.cinema.filter;

import com.cinema.audit.AuditService;
import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BranchScopeRBACFilter enforces role-based access control and branch scope on protected requests.
 * Returns 403 Forbidden for unauthorized access and audits violations.
 *
 * Defense-in-depth layer — primary authorization must be done in controllers (controller-level checks
 * with {@code AccessScope.can(...)} are the source of truth). This filter ensures that even direct URL
 * access without controller-level guards cannot bypass basic authorization.
 */
public final class BranchScopeRBACFilter implements Filter {
    private static final Logger logger = Logger.getLogger(BranchScopeRBACFilter.class.getName());
    private final AuditService auditService;

    // Endpoint whitelist for guest (unauthenticated) access
    private static final Set<String> GUEST_PREFIXES = Set.of(
            "/login", "/register", "/logout", "/admin/login",
            "/forgot-password",
            "/movie-detail",
            "/movie/genres",
            "/payment", "/vnpay", "/discover",
            "/assets/", "/favicon.ico", "/api/session",
            "/api/showtimes/browse", "/api/movies/browse",
            "/health", "/__test-error"
    );

    // Endpoint pattern that is public
    private static final Pattern SHOWTIME_DETAIL_PATTERN = Pattern.compile(".*/showtime/\\d+");

    // Customer-only protected endpoint prefixes
    private static final Set<String> CUSTOMER_PREFIXES = Set.of(
            "/api/profile", "/wallet", "/notification", "/booking/mine", "/booking/validate-own",
            "/api/bookings", "/api/transaction-history", "/concession/orders/mine",
            "/concession/products", "/concession/order", "/concession/orders/",
            "/movie",
            "/branch",
            "/voucher/available",
            "/loyalty/info",
            "/profile",
            // Customer booking flow (hold seat → confirm → cancel)
            "/booking/hold", "/booking/confirm", "/booking/cancel",
            // Customer booking page
            "/booking",
            // Reviews / Support / Complaints (RSC-2..4) — Customer submits, Manager reads via own role.
            "/api/reviews", "/api/support-requests", "/api/complaints"
    );

    // Staff/Manager endpoint prefixes (controller-level will further enforce branch scope)
    private static final Set<String> STAFF_PREFIXES = Set.of(
            "/shift", "/screen", "/showtime",
            "/inventory", "/concession/orders", "/report",
            "/branch/active", "/api/branches/active", "/api/branch",
            "/branch", "/movie",
            // Workspace APIs (Dashboard + Reports + Notifications + Profile)
            "/api/dashboard", "/api/notification",
            // Notification + Profile endpoints shared across internal users + customers.
            // Putting them in STAFF_PREFIXES lets Admin/Manager/Staff also read/update
            // their own notifications and profile without being blocked as 403.
            "/notification", "/api/profile",
            // Booking & ticket list (admin/manager view)
            "/api/booking", "/booking",
            // Concession products + orders + pickup (Staff sells, Manager deactivates)
            "/api/concession", "/concession",
            // Pricing rules (read for Staff, write for Admin)
            "/api/pricing", "/pricing",
            // Inventory + restock + adjust (Staff/Manager)
            "/api/inventory",
            // Loyalty points — staff adjust + view customer balance
            "/loyalty",
            // Complaints + Support inbox (Manager reads + replies, Customer submits)
            "/api/complaints", "/api/support-requests",
            // Users (Admin CRUD)
            "/api/users",
            "/api/staff-assignments",
            // Wallet admin view
            "/admin/wallet",
            // Payroll self-service and complaint endpoints; handlers enforce role and branch access.
            "/payroll", "/api/payroll-complaints",
            // Showtime allocation (Admin: all; Manager: own branch only — enforced in controller).
            "/api/showtime-allocations",
            // Voucher CRUD (Admin/Manager per scope; Staff can read holiday for context)
            "/voucher", "/holiday",
            // Work Scheduling
            "/shift-templates", "/shift-assignments", "/shift-requests",
            "/branch-schedule-config",
            // Staff work-shift check-in / check-out (spec staff-shift-checkin)
            "/shift-attendance",
            // Reviews — public read; Staff allowed too (controller lets any logged-in read).
            "/api/reviews"
    );

    // Admin-only endpoint prefixes (controller-level will re-check, this filter is just first line)
    private static final Set<String> ADMIN_ONLY_PREFIXES = Set.of(
            "/pricing",
            "/user", "/employee", "/staff-assignment",
            "/audit", "/upload"
    );

    public BranchScopeRBACFilter() {
        try {
            this.auditService = new AuditService();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BranchScopeRBACFilter", e);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        AccessScope scope = (AccessScope) httpRequest.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null) {
            // AuthFilter should have set this, but handle gracefully
            scope = AccessScope.forGuest();
        }

        String path = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();
        String contextPath = httpRequest.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        if (!isAuthorized(scope, path, method)) {
            auditUnauthorizedAccess(scope, path, method);

            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json;charset=UTF-8");

            ErrorEnvelope error = new ErrorEnvelope("FORBIDDEN",
                    "Bạn không có quyền truy cập tài nguyên này.");
            String json = SerializationUtil.toJson(error);
            httpResponse.getWriter().write(json);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Determine if access is authorized for this request.
     */
    private boolean isAuthorized(AccessScope scope, String path, String method) {
        // 1. Public endpoints (guest-allowed)
        if (isPublicEndpoint(path)) {
            return true;
        }

        // Let guests see the booking entry page, which explains that login is required.
        if (scope.isGuest() && "GET".equalsIgnoreCase(method) && "/booking".equals(path)) {
            return true;
        }

        // 2. Guest trying to access protected endpoint
        if (scope.isGuest()) {
            return false;
        }

        // 3. Admin can access everything (controller-level still re-checks fine-grained)
        if (scope.role() == Role.ADMIN) {
            return true;
        }

        // 4. Customer
        if (scope.role() == Role.CUSTOMER) {
            return isCustomerAuthorized(path);
        }

        // 5. Branch Manager / Branch Staff
        if (scope.role() == Role.BRANCH_MANAGER || scope.role() == Role.BRANCH_STAFF) {
            // Filter chỉ check role-level permission; việc user có quyền truy cập
            // ĐÚNG CHI NHÁNH hay không do controller enforce qua AccessScope.includesBranch()
            // vì filter không có đủ context về resource (vd. order của chi nhánh nào).
            if (scope.role() == Role.BRANCH_MANAGER && path.equals("/upload")) {
                return true;
            }
            return isStaffAuthorized(path);
        }

        return false;
    }

    private boolean isCustomerAuthorized(String path) {
        // Customer-specific endpoints are explicitly whitelisted
        for (String prefix : CUSTOMER_PREFIXES) {
            if (path.startsWith(prefix) || path.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStaffAuthorized(String path) {
        // Staff/Manager endpoints — controller enforces branch scope
        for (String prefix : STAFF_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determine if endpoint is publicly accessible without authentication.
     */
    private boolean isPublicEndpoint(String path) {
        for (String prefix : GUEST_PREFIXES) {
            if (path.startsWith(prefix) || path.equals(prefix)) {
                return true;
            }
        }
        Matcher m = SHOWTIME_DETAIL_PATTERN.matcher(path);
        if (m.matches()) {
            return true;
        }
        // Console HTML shell and JSP public pages — accessible to all (UI-only)
        if (path.contains("/console") || path.equals("/") || path.endsWith(".jsp")
                || path.contains("/showtime/discovery") || path.endsWith("/vnpay-result.jsp")
                || path.endsWith("/vnpay-result")
                || path.startsWith("/vnpay/demo") || path.startsWith("/vnpay/return")) {
            return true;
        }
        return false;
    }

    /**
     * Audit unauthorized access attempts.
     */
    private void auditUnauthorizedAccess(AccessScope scope, String path, String method) {
        try {
            String roleStr = scope.isGuest() ? "GUEST" : scope.role().toString();
            try (var conn = dal.DBContext.getConnection()) {
                auditService.record(conn, null, "UNAUTHORIZED_ACCESS", "REQUEST",
                        null, null,
                        String.format("role=%s method=%s path=%s", roleStr, method, path),
                        AuditService.FAILURE);
            }
        } catch (Exception e) {
            logger.warning("Failed to audit unauthorized access: " + e.getMessage());
        }
    }
}
