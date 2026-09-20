package com.cinema.auth;

import com.cinema.common.RequestValidator;
import com.cinema.common.ServiceException;
import com.cinema.common.ValidationErrors;
import com.cinema.util.PasswordUtil;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** Service for customer registration, login, and account management. */
public class AuthService {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^0\\d{9}$"); // Vietnamese mobile format
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_WINDOW_MINUTES = 15;
    private static final String GENERIC_LOGIN_ERROR = "Email hoặc mật khẩu không hợp lệ";
    private static final String ACCOUNT_LOCKED_ERROR = "Tài khoản của bạn đã bị khóa do nhiều lần đăng nhập sai. Vui lòng thử lại sau 15 phút.";
    private final UserDAO userDao;
    private final CustomerProfileDAO profileDao;
    private final AccessAssignmentService assignmentService;

    public AuthService(UserDAO userDAO, CustomerProfileDAO profileDAO) {
        this.userDao = userDAO;
        this.profileDao = profileDAO;
        this.assignmentService = null;
    }

    public AuthService(UserDAO userDAO, CustomerProfileDAO profileDAO, AccessAssignmentService assignmentService) {
        this.userDao = userDAO;
        this.profileDao = profileDAO;
        this.assignmentService = assignmentService;
    }

    /**
     * Register a new customer with email, phone, password, and name.
     * Creates user_account and customer_profile atomically.
     */
    public User registerCustomer(RegisterRequest request) throws Exception {
        // Hợp nhất email + phone vào cùng một thông báo "đã được đăng ký" để tránh
        // lộ thông tin email/phone nào đã tồn tại trong hệ thống (user enumeration).
        ValidationErrors errors = RequestValidator.builder()
            .required("email", request.email)
            .email("email", request.email)
            .required("phone", request.phone)
            .required("password", request.password)
            .required("fullName", request.fullName)
            .minLength("fullName", request.fullName, 2)
            .build();

        if (!errors.isEmpty()) {
            throw new ServiceException.Validation("Dữ liệu đầu vào không hợp lệ");
        }

        // Số điện thoại VN bắt buộc đầu 0 + 9 chữ số (10 số tổng) — khớp format
        // mà nhà mạng + OTP gateway (VNPay/SMS) đang dùng để nhất quán dữ liệu.
        if (!PHONE_PATTERN.matcher(request.phone).matches()) {
            throw new ServiceException.Validation("Số điện thoại không đúng định dạng (cần 0xxxxxxxxx)");
        }

        // Yêu cầu độ mạnh tối thiểu để chặn các mật khẩu phổ biến trong dictionary attack.
        if (!PasswordUtil.isStrong(request.password)) {
            throw new ServiceException.Validation("Mật khẩu phải có ít nhất 8 ký tự, bao gồm chữ và số");
        }

        if (userDao.findByEmail(request.email).isPresent()) {
            throw new ServiceException.Conflict("Email hoặc số điện thoại đã được đăng ký. Hãy thử đăng nhập.");
        }

        if (userDao.findByPhone(request.phone).isPresent()) {
            throw new ServiceException.Conflict("Email hoặc số điện thoại đã được đăng ký. Hãy thử đăng nhập.");
        }

        String passwordHash = PasswordUtil.hash(request.password);
        User user = new User(request.email, request.phone, passwordHash, request.fullName, Role.CUSTOMER);

        userDao.insert(user);

        // Mỗi customer luôn có một customer_profile rỗng đi kèm để các service khác
        // (loyalty, wallet, purchase history) có thể JOIN mà không phải null-check.
        CustomerProfile profile = new CustomerProfile(user.id());
        profileDao.insert(profile);

        return user;
    }

    /**
     * Authenticate customer with email and password.
     * Returns user if credentials valid, throws otherwise.
     * Enforces account lockout after 5 failed attempts in 15 minutes.
     */
    public User login(String email, String password) throws Exception {
        User user = userDao.findByEmail(email)
            .orElseThrow(() -> new ServiceException.Unauthorized(GENERIC_LOGIN_ERROR));

        if (user.isLocked()) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lockedUntil = user.lockedUntil();

            // Lock chưa hết hạn → từ chối ngay, không cần đụng tới password verify.
            if (lockedUntil != null && lockedUntil.isAfter(now)) {
                throw new ServiceException.Unauthorized(ACCOUNT_LOCKED_ERROR);
            }
            // Lock đã hết hạn — xóa để user có cơ hội đăng nhập lại từ đầu.
            userDao.updateLockedUntil(user.id(), null);
            userDao.updateFailedLoginCount(user.id(), 0);
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        }

        // Sai mật khẩu → tăng bộ đếm failed; nếu đạt ngưỡng thì set lockedUntil
        // và trả về thông báo khóa để chặn brute-force ngay từ lần này.
        if (!PasswordUtil.verify(password, user.passwordHash())) {
            int newCount = user.failedLoginCount() + 1;
            userDao.updateFailedLoginCount(user.id(), newCount);

            if (newCount >= MAX_FAILED_ATTEMPTS) {
                LocalDateTime lockUntil = LocalDateTime.now().plusMinutes(LOCKOUT_WINDOW_MINUTES);
                userDao.updateLockedUntil(user.id(), lockUntil);
                throw new ServiceException.Unauthorized(ACCOUNT_LOCKED_ERROR);
            }

            throw new ServiceException.Unauthorized(GENERIC_LOGIN_ERROR);
        }

        // Đăng nhập thành công — reset bộ đếm và cập nhật last_login để audit/log.
        userDao.resetFailedLoginCount(user.id());
        userDao.updateLastLoginAt(user.id(), LocalDateTime.now());

        // Đọc lại user từ DB để trả về snapshot mới nhất (passwordHash + flags) tránh
        // caller dùng dữ liệu đã cũ trong bộ nhớ.
        return userDao.findByEmail(email).orElseThrow();
    }

    /**
     * Update customer profile (name and phone).
     */
    public User updateProfile(Long userId, String fullName, String phone) throws Exception {
        ValidationErrors errors = RequestValidator.builder()
            .required("fullName", fullName)
            .minLength("fullName", fullName, 2)
            .required("phone", phone)
            .build();

        if (!errors.isEmpty()) {
            throw new ServiceException.Validation("Dữ liệu đầu vào không hợp lệ");
        }

        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new ServiceException.Validation("Số điện thoại không đúng định dạng");
        }

        // Phone uniqueness được enforce bên DAO (race-condition-safe qua UNIQUE index),
        // service chỉ cần truyền giá trị xuống và để DAO ném Conflict nếu trùng.
        userDao.updateProfile(userId, fullName, phone);

        return userDao.findById(userId)
            .orElseThrow(() -> new ServiceException.NotFound("Người dùng không tồn tại"));
    }

    /**
     * Get customer purchase history (tickets + F&B orders).
     * IDOR protected: returns history only for the authenticated customer.
     */
    public java.util.List<Transaction> getPurchaseHistory(Long customerId, int limit, int offset) throws Exception {
        PurchaseHistoryDAO historyDao = new PurchaseHistoryDAO();
        return historyDao.findCustomerTransactions(customerId, limit, offset);
    }

    /**
     * Get a single transaction with IDOR protection.
     */
    public Transaction getTransaction(Long transactionId, Long requestingCustomerId) throws Exception {
        PurchaseHistoryDAO historyDao = new PurchaseHistoryDAO();
        Transaction tx = historyDao.findById(transactionId, requestingCustomerId);
        
        if (tx == null) {
            throw new ServiceException.NotFound("Giao dịch không tồn tại hoặc bạn không có quyền truy cập");
        }
        
        return tx;
    }

    /**
     * Request object for customer registration.
     */
    public static class RegisterRequest {
        public final String email;
        public final String phone;
        public final String password;
        public final String fullName;

        public RegisterRequest(String email, String phone, String password, String fullName) {
            this.email = email;
            this.phone = phone;
            this.password = password;
            this.fullName = fullName;
        }
    }
}
