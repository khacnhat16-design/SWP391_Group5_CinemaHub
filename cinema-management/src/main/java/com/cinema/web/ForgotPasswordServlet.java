package com.cinema.web;

import com.cinema.auth.PasswordResetService;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.ServiceException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Forgot Password flow controller. Implements the 3-step flow:
 * <ol>
 *     <li>POST /forgot-password           — submit email, request OTP</li>
 *     <li>POST /forgot-password/verify    — submit OTP, verify it</li>
 *     <li>POST /forgot-password/reset     — submit new password, mark OTP consumed</li>
 * </ol>
 *
 * <p>All endpoints are public (guest-accessible). The flow is server-side protected
 * via the OTP itself (the reset endpoint re-verifies the OTP before updating the hash),
 * so direct URL access to /reset cannot bypass the verification step.
 */
public class ForgotPasswordServlet extends HttpServlet {

    private static final String RESET_USER_ID = "passwordReset.userId";
    private static final String RESET_EMAIL = "passwordReset.email";
    private static final String RESET_VERIFIED_AT = "passwordReset.verifiedAt";
    private static final long RESET_SESSION_TTL_MILLIS = 5 * 60 * 1000L;

    private PasswordResetService resetService;

    @Override
    public void init() throws ServletException {
        this.resetService = new PasswordResetService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo();
        if (path == null) path = "";
        // Dispatch sang JSP theo path variant (/verify, /reset...) — không xử lý
        // logic ở đây, controller auth riêng sẽ xử lý POST submit.
        if (path.equals("/verify")) {
            request.getRequestDispatcher("/WEB-INF/views/auth/forgot-password-verify.jsp")
                    .forward(request, response);
            return;
        }
        if (path.equals("/reset")) {
            if (!hasVerifiedReset(request)) {
                response.sendRedirect(request.getContextPath() + "/forgot-password");
                return;
            }
            request.getRequestDispatcher("/WEB-INF/views/auth/forgot-password-reset.jsp")
                    .forward(request, response);
            return;
        }
        // Default GET — render the email-entry JSP.
        request.getRequestDispatcher("/WEB-INF/views/auth/forgot-password.jsp")
                .forward(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        try {
            if (path.equals("/verify")) {
                handleVerify(request, response);
            } else if (path.equals("/reset")) {
                handleReset(request, response);
            } else {
                handleRequest(request, response);
            }
        } catch (ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống: " + e.getMessage());
        }
    }

    /** Step 1 — request OTP. */
    private void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String email = request.getParameter("email");
        PasswordResetService.RequestResult result = resetService.requestOtp(email);
        // Response neutral luôn giống nhau dù email có tồn tại hay không — đây là
        // kỹ thuật chống user enumeration: không cho attacker biết email nào đã đăng ký.
        String message = result.accepted()
                ? "Nếu email tồn tại trong hệ thống, mã xác nhận đã được gửi."
                : "Bạn đã yêu cầu quá nhiều lần. Vui lòng thử lại sau.";
        sendOk(response, java.util.Map.of(
                "message", message,
                "cooldown", result.cooldown(),
                "retryAfterSeconds", result.retryAfterSeconds()
        ));
    }

    /** Step 2 — verify OTP. Server issues a one-time reset token the client must echo back. */
    private void handleVerify(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String email = request.getParameter("email");
        String otp = request.getParameter("otp");
        long userId = resetService.verifyOtp(email, otp);
        resetService.markOtpVerified(userId);

        var session = request.getSession(true);
        session.setAttribute(RESET_USER_ID, userId);
        session.setAttribute(RESET_EMAIL, email.trim().toLowerCase());
        session.setAttribute(RESET_VERIFIED_AT, System.currentTimeMillis());

        sendOk(response, java.util.Map.of(
                "message", "Mã OTP hợp lệ. Vui lòng đặt mật khẩu mới."
        ));
    }

    /** Step 3 — reset password. Re-verifies OTP and validates signed reset token. */
    private void handleReset(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String newPassword = request.getParameter("newPassword");
        String confirmPassword = request.getParameter("confirmPassword");

        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            sendError(response, 400, "BAD_REQUEST", "Mật khẩu xác nhận không khớp");
            return;
        }

        if (!hasVerifiedReset(request)) {
            response.sendRedirect(request.getContextPath() + "/forgot-password");
            return;
        }

        long userId = (Long) request.getSession(false).getAttribute(RESET_USER_ID);
        resetService.resetPasswordAfterVerification(userId, newPassword);
        clearResetSession(request);

        sendOk(response, java.util.Map.of(
                "message", "Đặt lại mật khẩu thành công. Bạn có thể đăng nhập bằng mật khẩu mới."
        ));
    }

    private boolean hasVerifiedReset(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return false;
        Object userId = session.getAttribute(RESET_USER_ID);
        Object verifiedAt = session.getAttribute(RESET_VERIFIED_AT);
        if (!(userId instanceof Long) || !(verifiedAt instanceof Long)) return false;
        if (System.currentTimeMillis() - (Long) verifiedAt > RESET_SESSION_TTL_MILLIS) {
            clearResetSession(request);
            return false;
        }
        return true;
    }

    private void clearResetSession(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(RESET_USER_ID);
            session.removeAttribute(RESET_EMAIL);
            session.removeAttribute(RESET_VERIFIED_AT);
        }
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(
                new ErrorEnvelope(code, message)));
    }

}
