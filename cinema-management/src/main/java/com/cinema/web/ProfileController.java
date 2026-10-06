package com.cinema.web;

import com.cinema.auth.AuthService;
import com.cinema.auth.CustomerProfile;
import com.cinema.auth.CustomerProfileDAO;
import com.cinema.auth.Role;
import com.cinema.auth.User;
import com.cinema.auth.UserDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.wallet.Wallet;
import com.cinema.wallet.WalletDAO;
import com.cinema.wallet.WalletService;
import com.cinema.auth.AccessScope;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Customer profile API used by /console profile workspace. */
public final class ProfileController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(ProfileController.class.getName());

    private UserDAO userDao;
    private CustomerProfileDAO profileDao;
    private AuthService authService;
    private WalletService walletService;

    @Override
    public void init() throws ServletException {
        this.userDao = new UserDAO();
        this.profileDao = new CustomerProfileDAO();
        this.authService = new AuthService(userDao, profileDao);
        this.walletService = new WalletService(new WalletDAO());
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
        Map<String, Object> walletData = new LinkedHashMap<>();

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

        Wallet wallet = walletService.getWallet(userId);
        walletData.put("balance", wallet.balance());
        walletData.put("version", wallet.version());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", account);
        result.put("profile", membership);
        result.put("wallet", walletData);
        return result;
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
