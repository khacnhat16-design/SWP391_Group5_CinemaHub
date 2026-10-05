package com.cinema.filter;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.auth.StaffBranchAssignmentDAO;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Set;

/**
 * AuthFilter resolves session information into AccessScope.
 * Stores AccessScope as request attribute "accessScope" for use by other filters/controllers.
 * Distinguishes Guest, Customer, Admin, Branch Manager/Staff with branch scope.
 */
public final class AuthFilter implements Filter {
    public static final String SCOPE_ATTRIBUTE = "accessScope";

    private final StaffBranchAssignmentDAO assignmentDao;

    public AuthFilter() {
        try {
            this.assignmentDao = new StaffBranchAssignmentDAO();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize AuthFilter", e);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        AccessScope scope = resolveAccessScope(httpRequest);
        httpRequest.setAttribute(SCOPE_ATTRIBUTE, scope);

        System.out.println("[Auth-DEBUG] path=" + httpRequest.getRequestURI() + " role=" + (scope.isGuest() ? "GUEST" : scope.role()) + " perms=" + (scope.isGuest() ? "-" : scope.can("SHIFT_TEMPLATE_MANAGE")));
        chain.doFilter(request, response);
    }

    private AccessScope resolveAccessScope(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        // No session = guest access
        if (session == null) {
            return AccessScope.forGuest();
        }

        Long userId = (Long) session.getAttribute("userId");
        Object roleObj = session.getAttribute("role");

        if (userId == null || roleObj == null) {
            return AccessScope.forGuest();
        }

        // Role được lưu dạng String trong session (tương thích JSP cũ); parse
        // sang enum để type-safe. Sai format hoặc role lạ → rơi về Guest.
        Role role;
        try {
            role = Role.valueOf((String) roleObj);
        } catch (ClassCastException | IllegalArgumentException e) {
            return AccessScope.forGuest();
        }

        // Admin and Customer have no branch scope
        if (role == Role.ADMIN) {
            return AccessScope.forAuthenticatedUser(role, Set.of(), userId);
        }
        if (role == Role.CUSTOMER) {
            return AccessScope.forAuthenticatedUser(role, Set.of(), userId);
        }

        // Branch Manager/Staff: resolve active branches
        try {
            Set<Long> branchIds = assignmentDao.findActiveBranchesByUser(userId);
            return AccessScope.forAuthenticatedUser(role, branchIds, userId);
        } catch (Exception e) {
            // If we fail to load branches, deny access (safe default)
            return AccessScope.forGuest();
        }
    }
}
