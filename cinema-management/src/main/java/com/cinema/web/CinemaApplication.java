package com.cinema.web;

import com.cinema.filter.CsrfFilter;
import com.cinema.filter.EncodingFilter;
import com.cinema.filter.ExceptionMappingFilter;
import com.cinema.filter.AuthFilter;
import com.cinema.filter.BranchScopeRBACFilter;
import com.cinema.filter.NoCacheFilter;
import org.apache.catalina.Context;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.jasper.servlet.JasperInitializer;
import org.apache.jasper.servlet.JspServlet;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import java.io.File;

/** Local development entry point. Start it from VS Code with `mvn exec:java`. */
public final class CinemaApplication {
    private static void registerFilter(Context context, String name, Class<? extends jakarta.servlet.Filter> filterClass) {
        FilterDef definition = new FilterDef();
        definition.setFilterName(name);
        definition.setFilterClass(filterClass.getName());
        context.addFilterDef(definition);

        FilterMap mapping = new FilterMap();
        mapping.setFilterName(name);
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);
    }

    private CinemaApplication() { }

    public static void main(String[] args) throws Exception {
        loadEnvFile();
        String environmentPort = System.getenv("CINEMA_PORT");
        int port = resolvePort(System.getProperty("cinema.port"), environmentPort, "8080");
        Tomcat tomcat = start(port);
        System.out.printf("Cinema Management is running at http://localhost:%d/%n",
                tomcat.getConnector().getLocalPort());
        tomcat.getServer().await();
    }

    public static int resolvePort(String... overrides) {
        for (String override : overrides) {
            if (override != null && !override.isBlank()) {
                return Integer.parseInt(override.trim());
            }
        }
        return 8080;
    }

    /** Khởi động Tomcat 10.1 embedded và deploy webapp; không block (dùng cho cả smoke test). */
    public static Tomcat start(int port) throws Exception {
        File webRootDir = new File("src/main/webapp");
        if (!webRootDir.isDirectory()) {
            webRootDir = new File("cinema-management/src/main/webapp");
        }
        String webRoot = webRootDir.getAbsoluteFile().toString();

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector();
        Context context = tomcat.addContext("", webRoot);
        // Fix: Add target/classes (Maven compile output) to the webapp classloader.
        // Without this, the WebappClassLoader only looks in WEB-INF/classes inside the
        // docBase (src/main/webapp), causing ClassNotFoundException for compiled classes.
        // webRootDir = src/main/webapp -> go up 3 levels to get project root
        File projectDir = webRootDir.getParentFile().getParentFile().getParentFile();
        File classesDir = new File(projectDir, "target/classes");
        if (classesDir.exists()) {
            org.apache.catalina.webresources.StandardRoot resources =
                    new org.apache.catalina.webresources.StandardRoot(context);
            resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes",
                    classesDir.getAbsolutePath(), "/"));

            // WebappClassLoader mặc định chỉ load class từ WEB-INF/classes bên trong
            // webapp — nhưng chúng ta cần cả dependency JAR từ Maven. Đăng ký từng
            // JAR làm webapp resource để ClassLoader nhìn thấy; nếu không sẽ
            // ClassNotFoundException khi filter/servlet dùng dependency trong contextInitialized.
            // (e.g. jakarta.servlet.ServletContextListener → NoClassDefFoundError.)
            File mavenRepo = resolveMavenLocalRepo();
            File webInfLib = new File(webRootDir, "WEB-INF/lib");
            webInfLib.mkdirs(); // ensure directory exists even if empty
            File[] jars = mavenRepo.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) {
                    resources.addPreResources(
                            new org.apache.catalina.webresources.JarResourceSet(
                                    resources, "/WEB-INF/lib/" + jar.getName(),
                                    jar.getAbsolutePath(), "/"));
                }
            }

            context.setResources(resources);
            // Restore parent classloader so listener/ServletContext etc. are found.
            // This is safe because every JAR is now also registered as a webapp
            // resource (above), so classes used in SessionController come from the
            // SAME WebappClassLoader → no dual-load, no ClassCastException.
            if (Thread.currentThread().getContextClassLoader() != null) {
                context.setParentClassLoader(Thread.currentThread().getContextClassLoader());
            }
            // setDelegate(true) để Tomcat ưu tiên parent classloader (exec classloader
            // chứa Maven JAR) trước. Tránh trường hợp cùng 1 class được load 2 lần
            // (một từ parent, một từ webapp) → request attribute có 2 bản khác nhau.
            org.apache.catalina.loader.WebappLoader loader =
                    new org.apache.catalina.loader.WebappLoader();
            loader.setDelegate(true);
            context.setLoader(loader);
        } else {
            System.err.println("WARNING: target/classes not found at " + classesDir.getAbsolutePath()
                    + " — listener registration will fail. Run 'mvn compile' first.");
        }
        context.addMimeMapping("css", "text/css;charset=UTF-8");
        context.addMimeMapping("js", "application/javascript;charset=UTF-8");
        context.addMimeMapping("png", "image/png");
        context.addMimeMapping("jpg", "image/jpeg");
        context.addMimeMapping("jpeg", "image/jpeg");
        context.addMimeMapping("webp", "image/webp");
        // Embedded Tomcat does not auto-register Jasper like a full Tomcat
        // deployment, so JSP forwards must be mapped explicitly.
        context.addServletContainerInitializer(new JasperInitializer(), null);
        Tomcat.addServlet(context, "jsp", new JspServlet());
        context.addServletMappingDecoded("*.jsp", "jsp");
        // Embedded Tomcat không đọc web.xml → phải map DefaultServlet cho
        // /assets/* thủ công, nếu không CinemaServlet sẽ nuốt mọi request
        // tĩnh và trả về 404 cho CSS/JS/image.
        Tomcat.addServlet(context, "staticAssets", new DefaultServlet());
        context.addServletMappingDecoded("/assets/*", "staticAssets");
        // ExpiryScheduler được khởi động qua listener — đăng ký thủ công vì
        // embedded mode bỏ qua web.xml listeners.
        context.addApplicationListener(com.cinema.listener.AppContextListener.class.getName());
        registerFilter(context, "exceptionMapping", ExceptionMappingFilter.class);
        registerFilter(context, "encoding", EncodingFilter.class);
        registerFilter(context, "auth", AuthFilter.class);
        registerFilter(context, "branchScopeRbac", BranchScopeRBACFilter.class);
        registerFilter(context, "csrf", CsrfFilter.class);
        registerFilter(context, "noCache", NoCacheFilter.class);
        // LƯU Ý: AuthFilter + BranchScopeRBACFilter KHÔNG đăng ký ở embedded dev path.
        // isPublicEndpoint() của RBAC filter không whitelist "/", "/assets/*", "/dashboard",
        // "/booking", "/concessions" → mọi trang public sẽ bị 403.
        // Ở deploy path (WEB-INF/web.xml) filter chain đầy đủ vẫn được áp dụng.
        Tomcat.addServlet(context, "cinemaServlet", new CinemaServlet());
        context.addServletMappingDecoded("/", "cinemaServlet");
        // Embedded server cần map thủ công các servlet của PageController cho /console/*
        // để khớp với web.xml production — tránh CinemaServlet nuốt mất các route
        // /console/{domain}/list dành cho trang quản lý Admin/Manager.
        Tomcat.addServlet(context, "pageController", new PageController());
        context.addServletMappingDecoded("/console", "pageController");
        context.addServletMappingDecoded("/console/*", "pageController");
        // REST API cho UI mới (design.md API contracts)
        Tomcat.addServlet(context, "sessionController", new SessionController());
        context.addServletMappingDecoded("/api/session", "sessionController");
        Tomcat.addServlet(context, "profileController", new ProfileController());
        context.addServletMappingDecoded("/api/profile", "profileController");
        Tomcat.addServlet(context, "authController", new com.cinema.auth.AuthController());
        context.addServletMappingDecoded("/register", "authController");
        context.addServletMappingDecoded("/login", "authController");
        context.addServletMappingDecoded("/admin/login", "authController");
        context.addServletMappingDecoded("/verify-email", "authController");
        context.addServletMappingDecoded("/logout", "authController");
        Tomcat.addServlet(context, "showtimeController", new ShowtimeController());
        context.addServletMappingDecoded("/showtime/*", "showtimeController");
        Tomcat.addServlet(context, "discoveryServlet", new DiscoveryServlet());
        context.addServletMappingDecoded("/discover", "discoveryServlet");
        Tomcat.addServlet(context, "bookingController", new BookingController());
        context.addServletMappingDecoded("/booking/*", "bookingController");
        Tomcat.addServlet(context, "bookingEntry", new BookingEntryServlet());
        context.addServletMappingDecoded("/booking", "bookingEntry");
        Tomcat.addServlet(context, "movieController", new MovieController());
        context.addServletMappingDecoded("/movie/*", "movieController");
        Tomcat.addServlet(context, "branchController", new BranchController());
        context.addServletMappingDecoded("/branch/*", "branchController");
        Tomcat.addServlet(context, "showtimeAllocationController",
                new ShowtimeAllocationController());
        context.addServletMappingDecoded("/api/showtime-allocations",
                "showtimeAllocationController");
        context.addServletMappingDecoded("/api/showtime-allocations/*",
                "showtimeAllocationController");
        Tomcat.addServlet(context, "screenController", new ScreenController());
        context.addServletMappingDecoded("/screen/*", "screenController");
        Tomcat.addServlet(context, "pricingController", new PricingController());
        context.addServletMappingDecoded("/pricing/*", "pricingController");
        Tomcat.addServlet(context, "priceTemplateController", new PriceTemplateController());
        context.addServletMappingDecoded("/pricing-templates", "priceTemplateController");
        context.addServletMappingDecoded("/pricing-templates/*", "priceTemplateController");
        Tomcat.addServlet(context, "notificationController", new NotificationController());
        context.addServletMappingDecoded("/notification/*", "notificationController");
        Tomcat.addServlet(context, "paymentController", new PaymentController());
        context.addServletMappingDecoded("/payment/*", "paymentController");
        Tomcat.addServlet(context, "walletController", new WalletController());
        context.addServletMappingDecoded("/wallet", "walletController");
        context.addServletMappingDecoded("/wallet/*", "walletController");
        // Image upload via UploadThing — Admin / Branch Manager only.
        // NOTE: Tomcat.addServlet(name, instance) does NOT honor @MultipartConfig annotation
        // on the servlet class for embedded deployment. We must register the multipart
        // config element explicitly so request.getPart() works; otherwise the servlet
        // throws "no multi-part configuration has been provided".
        jakarta.servlet.MultipartConfigElement mce =
                new jakarta.servlet.MultipartConfigElement(
                        "",                              // location (String)
                        5L * 1024 * 1024,                // maxFileSize (long)
                        5L * 1024 * 1024 + 1024,         // maxRequestSize (long)
                        1024 * 1024                      // fileSizeThreshold (int)
                );
        org.apache.catalina.startup.Tomcat.addServlet(context, "uploadServlet", new UploadServlet());
        org.apache.catalina.Wrapper uploadWrapper = (org.apache.catalina.Wrapper)
                context.findChild("uploadServlet");
        if (uploadWrapper != null) {
            uploadWrapper.setMultipartConfigElement(mce);
        }
        context.addServletMappingDecoded("/upload", "uploadServlet");
        // Notification bell cho header workspace
        Tomcat.addServlet(context, "workspaceNotifServlet", new WorkspaceNotificationServlet());
        context.addServletMappingDecoded("/api/notification/*", "workspaceNotifServlet");
        // Forgot Password flow (3 endpoints under one servlet)
        Tomcat.addServlet(context, "forgotPasswordServlet", new ForgotPasswordServlet());
        context.addServletMappingDecoded("/forgot-password", "forgotPasswordServlet");
        context.addServletMappingDecoded("/forgot-password/", "forgotPasswordServlet");
        context.addServletMappingDecoded("/forgot-password/*", "forgotPasswordServlet");
        // VNPay endpoints — Return URL và IPN phải khớp vnpay.returnUrl / vnpay.ipnUrl.
        Tomcat.addServlet(context, "vnPayController", new VnPayController());
        context.addServletMappingDecoded("/vnpay/*", "vnPayController");
        tomcat.start();
        tomcat.getHost().setBackgroundProcessorDelay(600);
        return tomcat;
    }

    /**
     * Load key=value pairs from {@code cinema-management/.env} (or project root/.env)
     * into System properties so UploadThingService / PaymentService etc. pick them up
     * without requiring each developer to set shell environment variables.
     * Lines starting with {@code #} are comments and are skipped.
     */
    private static void loadEnvFile() {
        File[] candidates = {
                new File("cinema-management/.env"),
                new File(".env"),
                new File(System.getProperty("user.dir"), "cinema-management/.env")
        };
        for (File f : candidates) {
            if (f.isFile()) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        int eq = trimmed.indexOf('=');
                        if (eq > 0) {
                            String key = trimmed.substring(0, eq).trim();
                            String val = trimmed.substring(eq + 1).trim();
                            if (val.length() >= 2
                                    && ((val.startsWith("\"") && val.endsWith("\""))
                                    || (val.startsWith("'") && val.endsWith("'")))) {
                                val = val.substring(1, val.length() - 1);
                            }
                            // Only set if not already in environment (shell env wins).
                            if (System.getProperty(key) == null && System.getenv(key) == null) {
                                System.setProperty(key, val);
                            }
                        }
                    }
                    System.out.println("[CinemaApplication] Loaded .env from: " + f.getAbsolutePath());
                    return;
                } catch (Exception e) {
                    System.err.println("[CinemaApplication] Failed to read .env: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Locate the Maven local repository directory so JAR dependencies can be registered
     * as Tomcat webapp resources. Checks {@code M2_REPO} env-var first, then
     * {@code user.home/.m2/repository} as fallback.
     */
    private static File resolveMavenLocalRepo() {
        String m2 = System.getenv("M2_REPO");
        if (m2 != null && !m2.isBlank()) {
            File f = new File(m2);
            if (f.isDirectory()) return f;
        }
        File home = new File(System.getProperty("user.home"));
        File m2Home = new File(home, ".m2/repository");
        if (m2Home.isDirectory()) return m2Home;
        // Fallback: assume Maven's computed classpath is correct and extract repo path
        // from one of the dependency JARs (e.g. tomcat-embed-core).
        String cp = System.getProperty("java.class.path");
        if (cp != null) {
            for (String entry : cp.split(File.pathSeparator)) {
                File f = new File(entry);
                if (f.isFile() && f.getName().startsWith("tomcat-embed-core")) {
                    // JAR lives at  ~/.m2/repository/org/apache/tomcat/embed/...
                    File repo = f.getParentFile(); // WEB-INF/lib or root
                    if (repo != null && repo.getName().equals("repository")) {
                        return repo;
                    }
                }
            }
        }
        return new File(home, ".m2/repository");
    }
}
