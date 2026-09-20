# Research & Design Decisions — multi-branch-cinema

## Summary
- **Feature**: `multi-branch-cinema`
- **Discovery Scope**: New Feature (greenfield) — full discovery
- **Key Findings**:
  - Ngăn double-booking đáng tin cậy nhất bằng khóa bi quan (`SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK)`) trên hàng ghế trong transaction ngắn, kết hợp unique constraint làm lưới an toàn cuối; khóa lạc quan (version column) phù hợp hơn cho các xung đột "sửa dữ liệu lỗi thời" (lịch chiếu, hồ sơ, số dư).
  - JSP/Servlet chuẩn hiện đại là Jakarta EE (`jakarta.servlet`, Tomcat 10+); MVC phân tầng với Front Controller theo miền + Servlet Filter cho cross-cutting (auth, scope, encoding, CSRF, audit) là mẫu phổ biến, dễ chia task song song theo miền.
  - Seat hold có hạn (10 phút) cần cơ chế giải phóng kép: lazy expiry khi truy vấn (đảm bảo đúng tức thì) + scheduler nền (đảm bảo dữ liệu sạch khi không có truy vấn); yêu cầu 8.6 (không đếm ghế hold hết hạn quá 30 giây) quyết định chu kỳ quét ≤ 30s.
  - Lưu ý: môi trường thực thi không có WebSearch hoạt động thực tế; các kết luận dựa trên mẫu thiết kế đã được kiểm chứng rộng rãi cho JDBC/Servlet, không có nguồn URL để trích dẫn.

## Research Log

### Kiểm soát đồng thời cho đặt ghế (Req 7, 8)
- **Context**: Hai khách cùng giữ/xác nhận một ghế — race condition kinh điển của hệ thống bán vé.
- **Sources Consulted**: Mẫu thiết kế phổ biến cho JDBC transaction isolation (không có URL — web search không khả dụng trong môi trường).
- **Findings**:
  - Khóa bi quan (`SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK)`): đảm bảo tuyệt đối không double-booking; lock giữ ở mức mili-giây nếu transaction ngắn; phù hợp contention cao (suất hot).
  - Khóa lạc quan (version column + `UPDATE ... WHERE version = ?`): throughput cao hơn, không deadlock, nhưng cần retry/UX xử lý xung đột.
  - Unique constraint `UNIQUE(showtime_id, seat_id)` trên bảng vé active: lưới an toàn cuối cùng nếu logic khóa có lỗi.
  - Giữ transaction đặt ghế tách khỏi xử lý thanh toán (thanh toán mock diễn ra ngoài lock).
- **Implications**: Kết hợp cả hai — pessimistic cho luồng giữ ghế/xác nhận vé (contention cao, đúng tuyệt đối), optimistic cho cập nhật hồ sơ/lịch chiếu (xung đột "dữ liệu lỗi thời" theo Req 15.5). Mọi số dư (ví, điểm, tồn kho, lượt voucher) dùng `UPDATE ... SET x = x - n WHERE x >= n` nguyên tử (guarded update) và kiểm tra affected-rows.

### Kiến trúc ứng dụng JSP/Servlet (toàn bộ Req)
- **Context**: Chọn mẫu kiến trúc cho 23 requirements, 5 vai trò, nhiều miền nghiệp vụ.
- **Findings**:
  - Jakarta EE 10 / Tomcat 10.1 / `jakarta.servlet.*`; Java 17.
  - MVC phân tầng: JSP+JSTL (view, không scriptlet) → Servlet controller theo miền → Service (business rules + transaction) → DAO (JDBC thuần + PreparedStatement) → Microsoft SQL Server 2022.
  - Filter chain cho cross-cutting: Encoding → Auth/Session → RBAC+BranchScope → CSRF → Audit.
  - Connection pooling: Tomcat JDBC Pool qua JNDI DataSource.
  - Transaction quản lý thủ công trong Service (setAutoCommit(false)/commit/rollback) vì không dùng EJB/CDI interceptor.
- **Implications**: Mỗi miền nghiệp vụ (branch, movie, screen, showtime, booking, payment, voucher, concession, loyalty, wallet, shift, report, notification) là một gói controller/service/dao riêng → chia task song song an toàn theo biên giới miền.

### Seat hold & giải phóng tự động (Req 7.4, 8.6, 18, 20.6, 21.7)
- **Context**: Nhiều thực thể có "thời hạn giữ" (ghế 10 phút, đơn pending 30 phút, thanh toán chờ).
- **Findings**:
  - Lazy expiry: mọi truy vấn trạng thái ghế coi `hold_expires_at < SYSUTCDATETIME()` như ghế trống — đúng tức thì, không phụ thuộc scheduler.
  - Active expiry: `ServletContextListener` khởi động `ScheduledExecutorService` quét mỗi 30s, giải phóng hold hết hạn, hủy vé pending quá hạn, hủy đơn concession pending quá hạn.
  - Tổng quát hóa: cùng một mẫu "expiry-aware status" áp dụng cho seat hold, payment-pending ticket, concession order — giao diện chung `ExpirableResource`, nhưng mỗi miền tự sở hữu logic quét của mình.
- **Implications**: Req 8.6 thỏa mãn: truy vấn áp dụng lazy expiry nên số ghế trống luôn đúng; scheduler 30s đảm bảo bản ghi vật lý được dọn.

### Thanh toán mock & ví nội bộ (Req 9, 18)
- **Context**: Không tích hợp cổng thật; cần luồng thanh toán đủ thực tế để demo validation.
- **Findings**:
  - Mock gateway: servlet endpoint mô phỏng redirect + callback có token chống giả mạo nội bộ (HMAC với secret cấu hình); idempotency key cho callback (chống Req 9.7 xử lý trùng).
  - Ví nội bộ: guarded update số dư trong cùng transaction với xác nhận đơn — không cần distributed transaction.
- **Implications**: PaymentService định nghĩa interface `PaymentProvider` với 2 implementation: `MockGatewayProvider`, `CashProvider`, `WalletProvider`, `BankTransferProvider` (xác nhận tay) — Strategy pattern cho phương thức thanh toán.

### Tổng quát hóa chương trình tích điểm & voucher (Req 10, 17)
- **Context**: Voucher và tier discount đều là "giảm giá có điều kiện".
- **Findings**: Cả hai khớp mẫu `DiscountRule` (điều kiện áp dụng + cách tính giảm), nhưng nguồn dữ liệu và vòng đời khác nhau (voucher: mã + lượt dùng; tier: gắn tài khoản). Gộp chung một engine sẽ phức tạp hóa validation riêng của từng loại.
- **Implications**: Giữ 2 thành phần riêng (VoucherService, LoyaltyService) nhưng thống nhất thứ tự áp dụng trong PricingEngine: giá gốc → tier discount → voucher (theo Req 17.7). Ghi nhận đây là quyết định "không tổng quát hóa quá mức" (simplification).

## Architecture Pattern Evaluation

| Option | Description | Strengths | Risks / Limitations | Notes |
|--------|-------------|-----------|---------------------|-------|
| **MVC phân tầng theo miền (chọn)** | Filter → Servlet theo miền → Service → DAO | Đơn giản, quen thuộc với JSP/Servlet, biên miền rõ → task song song | Transaction thủ công, boilerplate DAO | Phù hợp quy mô học thuật/đồ án, đủ cho 23 req |
| Hexagonal / Ports-Adapters | Domain core + adapters | Testable, boundaries sạch | Overhead abstraction lớn với JDBC thuần, team nhỏ | Từ chối: quá nặng cho JSP/Servlet |
| EJB / CDI @Transactional | Container-managed transactions | Transaction tự động | Phụ thuộc server full-profile (WildFly), phức tạp triển khai Tomcat | Từ chối: Tomcat + JDBC thuần phổ biến và dễ chạy hơn |
| JPA/Hibernate thay JDBC | ORM | Giảm boilerplate | Ẩn SQL → khó kiểm soát UPDLOCK, HOLDLOCK, ROWLOCK/locking, khó dạy validation SQL | Từ chối: cần kiểm soát locking chính xác cho Req 8 |

## Design Decisions

### Decision: Pessimistic locking cho ghế, optimistic locking cho hồ sơ
- **Context**: Req 8 (double-booking), Req 15.5 (dữ liệu lỗi thời).
- **Selected Approach**: `SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK)` trên `showtime_seat` trong transaction giữ ghế/xác nhận; cột `version` trên `showtime`, `customer`, `wallet` cho cập nhật hồ sơ; guarded update (`WHERE stock >= qty`) cho tồn kho/ví/điểm/lượt voucher.
- **Rationale**: Ghế là điểm contention cao nhất và sai sót nghiêm trọng nhất; hồ sơ ít contention hơn và cần UX "tải lại dữ liệu mới".
- **Trade-offs**: Lock DB dưới tải cao — chấp nhận được vì transaction ngắn và quy mô demo.
- **Follow-up**: Load test 50 thread cùng giữ 1 ghế; xác nhận deadlock-free khi giữ nhiều ghế (luôn lock theo thứ tự seat_id tăng dần).

### Decision: Front Controller theo miền, không một dispatcher duy nhất
- **Context**: 23 requirements, nhiều team member song song.
- **Selected Approach**: Mỗi miền một servlet (`/booking/*`, `/showtime/*`, ...); không dùng mega-dispatcher.
- **Rationale**: Giảm xung đột merge, biên task rõ; @WebServlet annotation gọn.
- **Trade-offs**: Không có routing table tập trung — chấp nhận.

### Decision: Microsoft SQL Server 2022 + schema tập trung một file migration
- **Context**: Greenfield, cần ER rõ ràng.
- **Selected Approach**: `db/schema.sql` + `db/seed.sql`; SQL Server row-locking (bắt buộc cho row lock); UTF-8 collation.
- **Rationale**: SQL Server row-locking hỗ trợ `UPDLOCK, HOLDLOCK, ROWLOCK`; phổ biến trong môi trường học thuật VN.

### Decision: User Account thống nhất và Staff Branch Assignment hiệu lực
- **Context**: Req 13 cần 5 role và branch scope; schema cũ chỉ có customer.
- **Selected Approach**: `user_account` chung cho 4 role đăng nhập; `role` danh mục; `customer_profile` 1:1; `staff_branch_assignment` cho Manager/Staff; Guest không có account.
- **Rationale**: Một danh tính giúp session, audit, FK và scope nhất quán; đổi assignment có hiệu lực request kế tiếp.
- **Follow-up**: Seed bốn role đăng nhập và kiểm thử assignment ACTIVE/ENDED trên SQL Server.

### Decision: Không xây engine giảm giá hợp nhất
- **Context**: Tổng quát hóa voucher + tier (xem Research Log).
- **Selected Approach**: VoucherService và LoyaltyService riêng, PricingEngine điều phối thứ tự.
- **Rationale**: Simplification — validation của 2 loại khác nhau đủ nhiều để việc gộp làm tăng độ phức tạp.

## Risks & Mitigations
- **Deadlock khi giữ nhiều ghế không theo thứ tự** — Lock seat theo thứ tự `seat_id` tăng dần trong mọi transaction; timeout lock 5s.
- **Scheduler hết hạn không chạy khi app restart giữa chừng** — Lazy expiry đảm bảo tính đúng đắn bất kể scheduler; scheduler chỉ dọn vật lý.
- **Callback mock bị replay** — Idempotency key + trạng thái vé chỉ chuyển tiếp một chiều (pending → confirmed), lần hai nhận lỗi 9.7.
- **Transaction thủ công dễ quên rollback** — Mẫu try/catch/finally chuẩn hóa trong `TransactionTemplate` util; mọi Service dùng chung.
- **N+1 query khi render sơ đồ ghế** — Một query duy nhất join trạng thái ghế theo showtime; cache ngắn (5s) cho trang discovery.
- **Số dư điểm âm (Req 17.4)** — Chính sách HQ cấu hình: cho phép âm có cảnh báo (mặc định) — kiểm thử cả 2 nhánh.

## References
- Không có URL trích dẫn: WebSearch không khả dụng thực tế trong môi trường thực thi; các mẫu (pessimistic/optimistic locking, front controller, DAO/Service layering, lazy expiry) là kiến thức chuẩn đã kiểm chứng của hệ sinh thái Jakarta EE/JDBC/Microsoft SQL Server row-locking.
