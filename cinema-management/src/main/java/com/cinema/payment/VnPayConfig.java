package com.cinema.payment;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Cấu hình VNPay tập trung (Req 9, Payment Integration).
 * <p>Thứ tự ưu tiên đọc giá trị:
 * <ol>
 *   <li>System property {@code cinema.vnpay.*} (tiện cho dev/test, ghi đè khi cần).</li>
 *   <li>Environment variable {@code VNPAY_*}.</li>
 *   <li>File {@code vnpay.properties} trên classpath (production).</li>
 *   <li>Giá trị mặc định sandbox an toàn (URL sandbox, secret khác rỗng, env=SANDBOX).</li>
 * </ol>
 * <p><b>Không commit secret production</b> — nếu muốn dùng production hãy ghi đè qua biến môi trường
 * {@code VNPAY_HASH_SECRET} / {@code cinema.vnpay.hashSecret}.
 */
public final class VnPayConfig {
    private static final Logger logger = Logger.getLogger(VnPayConfig.class.getName());

    public static final String ENV_SANDBOX = "SANDBOX";
    public static final String ENV_PRODUCTION = "PRODUCTION";

    /** URL thanh toán — mặc định SANDBOX để không lỡ tay dùng production. */
    public static final String DEFAULT_PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    public static final String DEFAULT_SANDBOX_HOST = "sandbox.vnpayment.vn";
    public static final String DEFAULT_PRODUCTION_HOST = "vnpayment.vn";

    private final String environment;
    private final String tmnCode;
    private final String hashSecret;
    private final String payUrl;
    private final String returnUrl;
    private final String ipnUrl;
    private final int expireMinutes;

    private VnPayConfig(String environment, String tmnCode, String hashSecret, String payUrl,
                        String returnUrl, String ipnUrl, int expireMinutes) {
        this.environment = environment;
        this.tmnCode = tmnCode;
        this.hashSecret = hashSecret;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
        this.ipnUrl = ipnUrl;
        this.expireMinutes = expireMinutes;
    }

    public static VnPayConfig load() {
        Properties props = new Properties();
        try (InputStream in = VnPayConfig.class.getClassLoader()
                .getResourceAsStream("vnpay.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            logger.warning("Cannot read vnpay.properties: " + e.getMessage());
        }

        String environment = pick("vnpay.environment", "VNPAY_ENVIRONMENT",
                props, ENV_SANDBOX).toUpperCase();
        if (!ENV_SANDBOX.equals(environment) && !ENV_PRODUCTION.equals(environment)) {
            logger.warning("Unknown VNPAY_ENVIRONMENT=" + environment + ", force SANDBOX");
            environment = ENV_SANDBOX;
        }
        String defaultHost = ENV_PRODUCTION.equals(environment)
                ? "https://" + DEFAULT_PRODUCTION_HOST + "/paymentv2/vpcpay.html"
                : DEFAULT_PAY_URL;
        String tmnCode = pick("vnpay.tmnCode", "VNPAY_TMN_CODE", props,
                "4HP0IFJB");
        String hashSecret = pick("vnpay.hashSecret", "VNPAY_HASH_SECRET", props,
                "NTAPMXXMQJUWYXYQFRBQIQZZOQVESORY");
        String payUrl = pick("vnpay.payUrl", "VNPAY_PAY_URL", props, defaultHost);
        String returnUrl = pick("vnpay.returnUrl", "VNPAY_RETURN_URL", props,
                "http://localhost:8080/vnpay/return");
        String ipnUrl = pick("vnpay.ipnUrl", "VNPAY_IPN_URL", props,
                "http://localhost:8080/vnpay/ipn");
        int expire = Integer.parseInt(pick("vnpay.expireMinutes", "VNPAY_EXPIRE_MINUTES", props, "15"));

        if (ENV_SANDBOX.equals(environment) && payUrl.contains(DEFAULT_PRODUCTION_HOST)) {
            logger.warning("Refusing to point to PRODUCTION URL in SANDBOX mode → fallback to sandbox URL");
            payUrl = DEFAULT_PAY_URL;
        }

        VnPayConfig config = new VnPayConfig(environment, tmnCode, hashSecret, payUrl,
                returnUrl, ipnUrl, expire);
        logger.info("[VNPAY] Config loaded: env=" + environment
                + " tmnCode=" + tmnCode
                + " payUrl=" + payUrl
                + " returnUrl=" + returnUrl
                + " ipnUrl=" + ipnUrl
                + " expireMinutes=" + expire
                + " hashSecret=<redacted>");
        return config;
    }

    private static String pick(String propKey, String envKey, Properties props, String fallback) {
        String value = System.getProperty("cinema." + propKey);
        if (value != null && !value.isBlank()) return value.trim();
        value = System.getenv(envKey);
        if (value != null && !value.isBlank()) return value.trim();
        value = props.getProperty(propKey);
        if (value != null && !value.isBlank()) return value.trim();
        return fallback;
    }

    public String environment() { return environment; }
    public String tmnCode() { return tmnCode; }
    public String hashSecret() { return hashSecret; }
    public String payUrl() { return payUrl; }
    public String returnUrl() { return returnUrl; }
    public String ipnUrl() { return ipnUrl; }
    public int expireMinutes() { return expireMinutes; }
    public boolean isSandbox() { return ENV_SANDBOX.equals(environment); }
}
