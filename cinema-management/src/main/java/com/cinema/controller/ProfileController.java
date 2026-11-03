package com.cinema.controller;

import com.cinema.service.auth.AuthService;
import com.cinema.model.auth.CustomerProfile;
import com.cinema.dao.auth.CustomerProfileDAO;
import com.cinema.dao.loyalty.LoyaltyDAO;
import com.cinema.model.loyalty.LoyaltyTier;
import com.cinema.model.auth.Role;
import com.cinema.model.auth.User;
import com.cinema.dao.auth.UserDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.model.auth.AccessScope;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Customer profile API used by /console profile workspace. */
public final class ProfileController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(ProfileController.class.getName());

    private UserDAO userDao;
    private CustomerProfileDAO profileDao;
    private LoyaltyDAO loyaltyDao;
    private AuthService authService;

    @Override
    public void init() throws ServletException {
        this.userDao = new UserDAO();
        this.profileDao = new CustomerProfileDAO();
        this.loyaltyDao = new LoyaltyDAO();
        this.authService = new AuthService(userDao, profileDao);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            Long userId = requireCustomer(request, response);
            if (userId == null) return;
            sendOk(response, profilePayload(userId));
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            Long userId = requireCustomer(request, response);
            if (userId == null) return;
            if (hasBankAccountParameters(request)) {
                saveBankAccount(userId, request);
                sendOk(response, profilePayload(userId));
                return;
            }
            User updated = authService.updateProfile(userId,
                    request.getParameter("fullName"), request.getParameter("phone"));
            var session = request.getSession(false);
            if (session != null) session.setAttribute("username", updated.fullName());
            sendOk(response, profilePayload(userId));
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private Map<String, Object> profilePayload(long userId) throws Exception {
        Map<String, Object> account = new LinkedHashMap<>();
        Map<String, Object> membership = new LinkedHashMap<>();

        User user = userDao.findById(userId)
                .orElseThrow(() -> new ServiceException.NotFound("Tài khoản không tồn tại"));
        account.put("id", user.id());
        account.put("email", user.email());
        account.put("phone", user.phone());
        account.put("fullName", user.fullName());
        account.put("role", user.role() == null ? "CUSTOMER" : user.role().name());
        account.put("status", user.status());

        CustomerProfile profile = profileDao.findByUserId(userId)
                .orElseThrow(() -> new ServiceException.NotFound("Hồ sơ khách hàng không tồn tại"));
        membership.put("tier", profile.tier());
        membership.put("points", profile.points());
        List<LoyaltyTier> tiers = loyaltyDao.findAllTiers().stream()
                .filter(tier -> !"SYSTEM".equalsIgnoreCase(tier.tierName()))
                .toList();
        membership.put("tierProgressAvailable", !tiers.isEmpty());
        LoyaltyTier currentTier = tiers.stream()
                .filter(tier -> tier.tierName().equalsIgnoreCase(profile.tier()))
                .findFirst()
                .orElseGet(() -> tiers.stream()
                        .filter(tier -> tier.minPoints() <= profile.points())
                        .max(java.util.Comparator.comparingInt(LoyaltyTier::minPoints))
                        .orElse(null));
        LoyaltyTier nextTier = tiers.stream()
                .filter(tier -> tier.minPoints() > profile.points())
                .min(java.util.Comparator.comparingInt(LoyaltyTier::minPoints))
                .orElse(null);
        membership.put("tierProgressStartPoints",
                currentTier == null ? 0 : currentTier.minPoints());
        membership.put("nextTier", nextTier == null ? null : nextTier.tierName());
        membership.put("nextTierMinPoints", nextTier == null ? null : nextTier.minPoints());
        membership.put("pointsToNextTier",
                nextTier == null ? null : nextTier.minPoints() - profile.points());

        Map<String, Object> bankAccount = new LinkedHashMap<>();
        bankAccount.put("bankName", profile.bankName());
        bankAccount.put("bankAccountName", profile.bankAccountName());
        bankAccount.put("bankAccountNumber", profile.bankAccountNumber());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", account);
        result.put("profile", membership);
        result.put("bankAccount", bankAccount);
        return result;
    }

    private boolean hasBankAccountParameters(HttpServletRequest request) {
        return request.getParameter("bankName") != null
                || request.getParameter("bankAccountName") != null
                || request.getParameter("bankAccountNumber") != null;
    }

    private void saveBankAccount(long userId, HttpServletRequest request) throws Exception {
        String bankName = trimToNull(request.getParameter("bankName"));
        String accountName = trimToNull(request.getParameter("bankAccountName"));
        String accountNumber = trimToNull(request.getParameter("bankAccountNumber"));
        boolean allEmpty = bankName == null && accountName == null && accountNumber == null;
        if (!allEmpty) {
            if (bankName == null || accountName == null || accountNumber == null) {
                throw new ServiceException.Validation("Vui lòng nhập đầy đủ thông tin tài khoản ngân hàng");
            }
            if (bankName.length() > 120 || accountName.length() > 120) {
                throw new ServiceException.Validation("Tên ngân hàng và chủ tài khoản tối đa 120 ký tự");
            }
            if (!accountNumber.matches("[0-9]{6,40}")) {
                throw new ServiceException.Validation("Số tài khoản phải gồm 6–40 chữ số");
            }
        }
        profileDao.updateBankAccount(userId, bankName, accountName, accountNumber);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private Long requireCustomer(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
            sendError(response, 403, "FORBIDDEN", "Chi Customer moi duoc xem ho so");
            return null;
        }
        var session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute("userId");
        if (!(value instanceof Long userId)) {
            sendError(response, 401, "UNAUTHORIZED", "Can dang nhap");
            return null;
        }
        return userId;
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else {
            logger.log(Level.SEVERE, "Không thể tải hồ sơ khách hàng", e);
            sendError(response, 500, "INTERNAL_ERROR", "Loi he thong");
        }
    }
}
