# Kế hoạch triển khai — multi-branch-cinema

> Mỗi sub-task được thực hiện theo TDD: Viết code thất bại → triển khai tối thiểu → refactor → chạy lại toàn bộ test liên quan.
> Các task `(P)` chỉ chạy song song sau khi Foundation (1.1–1.5) hoàn tất; chúng có biên component không chồng lấn.

## 1. Foundation: project runtime, SQL Server và test harness

- [x] 1.1 Khởi tạo ứng dụng Jakarta Servlet/JSP chạy trên Tomcat 10.1
  - Cấu hình build Java 17, Jakarta Servlet 6, JSTL, Microsoft JDBC Driver for SQL Server 12.x và JUnit 5.
  - Cấu hình web.xml với UTF-8, session timeout 30 phút và thứ tự filter được thiết kế.
  - Hoàn tất khi ứng dụng deploy được lên Tomcat và trang health/index trả HTTP 200.
  - _Requirements: 15.1, 15.4_
  - _Boundary: Runtime configuration, WEB-INF/web.xml_

- [x] 1.2 Tạo DataSource SQL Server, transaction utility và cấu hình test database
  - Cấu hình JNDI `jdbc/cinemaSqlServer`, JDBC connection lifecycle và transaction commit/rollback nhất quán.
  - Tạo test hỗ trợ chạy migration trên SQL Server test và rollback dữ liệu từng test.
  - Hoàn tất khi test transaction chứng minh ghi dữ liệu thành công được commit và lỗi giữa chừng không để lại bản ghi nửa vời.
  - _Requirements: 15.2, 15.4_
  - _Boundary: DBUtil, TransactionTemplate, test infrastructure_

- [x] 1.3 Tạo schema SQL Server nền tảng và seed dữ liệu role
  - Viết migration theo đúng thứ tự khóa ngoại SQL Server: role/user_account/branch trước các entity phụ thuộc; dùng IDENTITY, NVARCHAR, DATETIME2, BIT và CHECK constraints.
  - Seed bốn role đăng nhập `ADMIN`, `BRANCH_MANAGER`, `BRANCH_STAFF`, `CUSTOMER` và dữ liệu demo tối thiểu.
  - Hoàn tất khi schema chạy sạch từ database rỗng và kiểm tra constraint/foreign key của User Account, role, branch đều pass.
  - _Requirements: 1.1, 13.1, 16.1_
  - _Boundary: db/schema.sql, db/seed.sql_

- [x] 1.4 Tạo shared request validation, error envelope và audit infrastructure
  - Chuẩn hóa field validation, lỗi 400/401/403/404/409/422/500 và không lộ stack trace cho client.
  - Tạo AuditService ghi actor, thời điểm, hành động, entity, trước/sau khi phù hợp và kết quả.
  - Hoàn tất khi một request validation lỗi trả field error và một thay đổi mẫu sinh audit record.
  - _Requirements: 15.1, 15.2, 15.3, 15.4_
  - _Boundary: common, audit_

- [ ] 1.5 Tạo base JSP layout, CSRF và filter chain cross-cutting
  - Triển khai Encoding, CSRF, exception mapping và layout JSP/JSTL không dùng scriptlet.
  - Hoàn tất khi POST thiếu CSRF token bị chặn, lỗi có trang/response thống nhất và view render UTF-8.
  - _Requirements: 15.1, 15.4_
  - _Boundary: Filter chain, JSP layout_

## 2. Identity, RBAC và branch scope

- [x] 2.1 Triển khai đăng ký, đăng nhập và khóa tài khoản Customer
  - Viết code trước cho email/phone duy nhất, mật khẩu không đạt chính sách, đăng nhập sai, khóa tạm sau 5 lần sai trong 15 phút và đăng nhập hợp lệ.
  - Tạo user_account + customer_profile nguyên tử khi Customer đăng ký và session có userId/role sau login.
  - Hoàn tất khi Customer đăng ký/đăng nhập qua JSP và các nhánh lỗi hiển thị thông báo chung đúng yêu cầu.
  - _Requirements: 16.1, 16.2, 16.3, 16.4_
  - _Boundary: AuthService, UserDAO, CustomerProfileDAO, AuthController_

- [x] 2.2 Triển khai gán role và phạm vi chi nhánh hiệu lực (P)
  - Viết code cho Admin gán/kết thúc assignment của Branch Manager/Branch Staff, chặn role/branch không hợp lệ và không tạo assignment ACTIVE trùng.
  - Thay đổi assignment phải được request tiếp theo nhận ngay mà không cần đăng nhập lại.
  - Hoàn tất khi Admin tạo/kết thúc assignment và bảng staff_branch_assignment phản ánh đúng effective scope.
  - _Requirements: 13.1, 13.4, 13.6_
  - _Boundary: AccessAssignmentService, RoleDAO, StaffBranchAssignmentDAO_
  - _Depends: 1.3, 2.1_

- [x] 2.3 Triển khai Authorization/BranchScope filter (P)
  - Phân biệt Guest, Customer, Admin, Branch Manager và Branch Staff; Guest chỉ vào public discovery, Customer chỉ vào dữ liệu sở hữu.
  - Ép branch scope trên read/write và audit mọi cố gắng truy cập trái phép.
  - Hoàn tất khi Staff branch A bị 403 khi truy vấn/sửa dữ liệu branch B, trong khi Admin xem được dữ liệu toàn chuỗi.
  - _Requirements: 13.1, 13.2, 13.3, 13.5, 13.6, 16.6_
  - _Boundary: AuthFilter, BranchScopeRBACFilter_
  - _Depends: 1.4, 1.5, 2.1, 2.2_

- [x] 2.4 Hoàn thiện profile Customer và lịch sử giao dịch sở hữu
  - Viết code cho cập nhật hồ sơ, unique phone, lịch sử vé/F&B theo thời gian giảm dần và chặn IDOR.
  - Hoàn tất khi Customer chỉ thấy lịch sử của chính mình gồm trạng thái giao dịch và điểm.
  - _Requirements: 16.5, 16.6, 16.7_
  - _Boundary: AuthService, CustomerProfileDAO, AuthController_
  - _Depends: 2.1_

## 3. Quản trị chi nhánh và danh mục phim

- [x] 3.1 (P) Triển khai quản lý chi nhánh Admin
  - Viết code cho normalize tên, unique không phân biệt hoa/thường, danh sách theo role và chặn vô hiệu hóa branch còn showtime tương lai.
  - Hoàn tất khi Admin CRUD branch qua giao diện, Guest/Customer chỉ xem branch ACTIVE và branch inactive chặn nghiệp vụ mới.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_
  - _Boundary: BranchService, BranchDAO, BranchController_
  - _Depends: 1.3, 2.3_

- [x] 3.2 (P) Triển khai danh mục phim tập trung
  - Viết code cho duration/ngày chiếu/rating, trạng thái publish, chặn xóa hoặc sửa duration/rating gây ảnh hưởng showtime tương lai.
  - Hoàn tất khi Admin quản phim và Branch Manager chỉ chọn được phim PUBLISHED trong khoảng hiệu lực.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
  - _Boundary: MovieService, MovieDAO, MovieController_
  - _Depends: 1.3, 2.3_

## 4. Phòng chiếu, ghế và lịch chiếu

- [ ] 4.1 Triển khai phòng chiếu và sơ đồ ghế theo chi nhánh (P)
  - Viết code tạo screen/mã phòng unique, kích thước ghế hợp lệ, sinh ghế theo hàng/cột/seat type, deactivate và cập nhật map có ràng buộc vé/hold.
  - Hoàn tất khi Branch Manager chỉ quản lý screen của branch mình và thay đổi map bị chặn khi có vé/hold bị tác động.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_
  - _Boundary: ScreenService, ScreenDAO, SeatDAO_
  - _Depends: 3.1, 2.3_

- [ ] 4.2 Triển khai tạo/cập nhật lịch chiếu có conflict validation
  - Viết code xu ly hai showtime giao thoa cùng screen, gồm cleaning buffer và hai request đồng thời.
  - Khóa/kiểm tra SQL Server phải cho đúng một request thắng khi lịch xung đột được tạo đồng thời.
  - Hoàn tất khi UI hiển thị chi tiết showtime xung đột và lịch có vé/hold không thể sửa/xóa trái quy tắc.
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_
  - _Boundary: ShowtimeService, ShowtimeDAO, ShowtimeController_
  - _Depends: 3.2, 4.1_

- [ ] 4.3 Triển khai trạng thái lifecycle lịch chiếu và discovery công khai
  - Tạo xử lý OPEN/ENDED/CANCELLED, chặn booking sau end+buffer và query Guest/Customer theo branch/phim/ngày.
  - Hoàn tất khi Guest xem được showtime mở bán cùng seat availability, còn showtime ENDED/CANCELLED không nhận hold mới.
  - _Requirements: 4.6, 4.7, 6.1, 6.2, 6.3, 6.4_
  - _Boundary: ShowtimeService, discovery JSP_
  - _Depends: 4.2_

## 5. Pricing, voucher và loyalty

- [ ] 5.1 (P) Triển khai price rules và PricingEngine
  - Viết code tổ hợp seat type/ngày/khung giờ unique, fallback default và snapshot giá sau khi rule đổi.
  - Hoàn tất khi Branch Manager xem được giá suy ra cho một showtime và giá đã bán không thay đổi hồi tố.
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_
  - _Boundary: PricingEngine, PriceRuleDAO_
  - _Depends: 1.3, 3.2_

- [ ] 5.2 (P) Triển khai voucher với usage guard đồng thời
  - Viết code validity, branch/movie/minimum order, lượt cuối cùng đồng thời và trả lại lượt khi hoàn tiền nếu refundable.
  - Hoàn tất khi hai yêu cầu dùng lượt cuối cùng chỉ có một yêu cầu được áp dụng, yêu cầu kia nhận lỗi cụ thể.
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_
  - _Boundary: VoucherService, VoucherDAO_
  - _Depends: 1.3, 3.1, 3.2_

- [ ] 5.3 (P) Triển khai loyalty points, membership tier và tier discount
  - Viết code cấu hình ngưỡng tăng dần, cộng/trừ ledger, nâng hạng, cấu hình không hồi tố và hai giao dịch cộng điểm đồng thời.
  - Hoàn tất khi trang tài khoản hiển thị points/tier/ledger và PricingEngine áp tier discount trước voucher.
  - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.6, 17.7, 17.8_
  - _Boundary: LoyaltyService, LoyaltyDAO_
  - _Depends: 1.3, 2.1, 5.1_

## 6. Ví khách hàng và thanh toán

- [ ] 6.1 (P) Triển khai ví Customer và ledger số dư
  - Viết code top-up bội số 10.000, top-up pending/success/failure, guarded spend, refund và hai spend đồng thời.
  - Hoàn tất khi số dư không âm, lịch sử ví hiển thị số dư sau mỗi giao dịch và account locked không thể nạp/tiêu.
  - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6, 18.7, 18.8_
  - _Boundary: WalletService, WalletDAO_
  - _Depends: 1.3, 2.1_

- [ ] 6.2 Triển khai payment providers và callback idempotency
  - Viết code Cash pending/confirm, mock callback HMAC success/failure, bank transfer staff confirm/reject và callback trùng.
  - Hoàn tất khi mỗi payment ghi đúng method/status và callback/Staff confirm lần hai trả `ALREADY_PROCESSED`.
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8_
  - _Boundary: PaymentService, PaymentProvider, PaymentDAO_
  - _Depends: 2.3, 6.1_

## 7. Booking, seat hold, hủy và soát vé

- [ ] 7.1 Triển khai tạo seat hold 10 phút và hiển thị trạng thái ghế
  - Viết integration test lock `WITH (UPDLOCK, HOLDLOCK, ROWLOCK)` theo seatId tăng dần cho một và nhiều ghế.
  - Coi hold hết hạn là AVAILABLE khi đọc; tạo hold 10 phút chỉ khi toàn bộ ghế trống.
  - Hoàn tất khi hai Customer giữ cùng ghế đồng thời thì đúng một thành công, view cập nhật trống/hold/sold và thời gian còn lại.
  - _Requirements: 7.1, 7.2, 7.3, 8.1, 8.3, 8.4, 8.5, 8.6_
  - _Boundary: BookingService, SeatHoldDAO, ShowtimeSeatDAO_
  - _Depends: 4.3, 5.1, 6.2_

- [ ] 7.2 Triển khai confirm booking và bán vé tại quầy
  - Kết hợp hold còn hiệu lực, tính giá, tier/voucher, payment idempotency, chuyển ghế SOLD và tạo ticket confirmed.
  - Thêm luồng Branch Staff bán vé tiền mặt trực tiếp nhưng vẫn lock ghế và ép branch scope.
  - Hoàn tất khi confirm sau expiry bị từ chối, nhóm ghế giao nhau chỉ có một ticket confirmed, vé lưu giá snapshot/branch/showtime/seat chính xác.
  - _Requirements: 7.5, 7.6, 7.7, 8.2, 9.3, 9.6, 9.7_
  - _Boundary: BookingService integration_
  - _Depends: 5.1, 5.2, 5.3, 6.2, 7.1_

- [ ] 7.3 Triển khai hủy vé và hoàn tiền bậc thang
  - Viết code mốc ≥24h/2–24h/<2h/USED, một ticket bị hủy đồng thời, hoàn voucher/điểm/ví và giải phóng ghế.
  - Hoàn tất khi ticket CANCELLED lưu số tiền hoàn đúng và ghế có thể bán lại ngay; ticket USED không thể hủy.
  - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 10.5, 17.4, 18.5_
  - _Boundary: BookingService cancellation integration_
  - _Depends: 5.2, 5.3, 6.1, 7.2_

- [ ] 7.4 Triển khai soát vé một lần tại đúng chi nhánh
  - Viết code ticket code không tồn tại/pending/cancelled/used/sai branch/showtime ended và hai lần quét đồng thời.
  - Hoàn tất khi Branch Staff quét vé hợp lệ chuyển ticket sang USED một lần, lưu thời gian/người soát và mọi lần sau bị chặn.
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_
  - _Boundary: BookingService ticket validation_
  - _Depends: 7.2, 2.3_

## 8. Concession catalog, inventory và bán tại quầy

- [ ] 8.1 (P) Triển khai sản phẩm/combo và tồn kho branch
  - Viết code product unique/positive price, combo component hợp lệ, chặn deactivate product còn nằm trong combo, nhập kho/kiểm kê có lý do và branch scope.
  - Hoàn tất khi hết tồn kho của branch làm sản phẩm unavailable tại cả kênh online/offline và giá đơn cũ không đổi khi Admin sửa giá.
  - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 19.7, 19.8_
  - _Boundary: ProductDAO, InventoryService, InventoryDAO_
  - _Depends: 1.3, 2.3, 3.1_

- [ ] 8.2 Triển khai POS bắp nước offline
  - Viết code create pending order, guarded stock cho hai Staff bán sản phẩm cuối cùng, cash/wallet confirm, điểm và hủy/hoàn có quyền Manager.
  - Hoàn tất khi biên nhận hiện cho đơn confirmed và một đơn vượt tồn kho đồng thời bị từ chối không trừ âm kho.
  - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.5, 20.6, 20.7_
  - _Boundary: ConcessionService offline sales_
  - _Depends: 5.3, 6.1, 8.1_

## 9. Pre-order bắp nước online và pickup

- [ ] 9.1 Triển khai pre-order gắn vé/suất chiếu
  - Viết code thêm F&B cùng booking, pre-order sau ticket confirmed, cutoff 30 phút, stock cuối cùng đồng thời và pending expiry.
  - Hoàn tất khi thanh toán thành công tạo READY_FOR_PICKUP, trừ tồn kho, cộng điểm và hiển thị pickup code duy nhất.
  - _Requirements: 21.1, 21.2, 21.3, 21.7, 21.8_
  - _Boundary: ConcessionService online preorder_
  - _Depends: 7.2, 8.1, 6.2_

- [ ] 9.2 Triển khai pickup, no-show và cascade cancellation
  - Viết code pickup code đúng branch/một lần, deadline no-show và hủy ticket trước pickup dẫn tới refund + hoàn kho.
  - Hoàn tất khi Staff chỉ giao được một lần, đơn quá hạn chuyển EXPIRED_NO_SHOW và ticket cancellation hủy đúng pre-order chưa nhận.
  - _Requirements: 21.4, 21.5, 21.6, 21.9_
  - _Boundary: ConcessionService pickup/cancellation integration_
  - _Depends: 7.3, 9.1_

## 10. Shift, reporting và notification

- [ ] 10.1 (P) Triển khai mở/đóng ca và đối soát tiền mặt
  - Viết code chặn hai ca OPEN của cùng Staff, gán giao dịch cash vào ca, tính expected cash, sai lệch vượt ngưỡng và Manager approve/return.
  - Hoàn tất khi Branch Manager xem được lịch sử xử lý ca và Admin xem tổng sai lệch toàn chuỗi.
  - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.5, 22.6, 22.7_
  - _Boundary: ShiftService, ShiftDAO_
  - _Depends: 2.3, 6.2, 8.2_

- [ ] 10.2 (P) Triển khai báo cáo doanh thu và vận hành có branch scope
  - Viết code khoảng ngày hợp lệ, totals confirmed/used/cancelled/refund, phân trang, scope Branch Manager và snapshot nhất quán khi booking/cancel đồng thời.
  - Hoàn tất khi Admin lọc toàn chuỗi theo branch/phim/ngày và Manager không thể đọc dữ liệu branch khác.
  - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_
  - _Boundary: ReportService, ReportController_
  - _Depends: 2.3, 7.3, 8.2, 10.1_

- [ ] 10.3 (P) Triển khai thông báo in-app và reminder
  - Viết code tạo notification confirmed/cancel/refund, danh sách chỉ thuộc owner, read/unread, reminder 60 phút và email failure không rollback giao dịch chính.
  - Hoàn tất khi Customer chỉ thấy thông báo của họ; account LOCKED không nhận marketing nhưng vẫn nhận thông báo hoàn tiền.
  - _Requirements: 23.1, 23.2, 23.3, 23.4, 23.5, 23.6_
  - _Boundary: NotificationService, NotificationDAO_
  - _Depends: 2.1, 7.2, 7.3_

## 11. Scheduler expiry và operational lifecycle integration

- [ ] 11.1 Triển khai ExpiryScheduler cho hold/order/showtime/reminder
  - Viết code scheduler xử lý hold hết hạn, ticket/order PENDING quá hạn, showtime end+buffer và reminder đúng thời điểm; các tác vụ chạy idempotent.
  - Hoàn tất khi scheduler quét mỗi 30 giây và dữ liệu hết hạn được dọn mà không ảnh hưởng transaction request đang chạy.
  - _Requirements: 4.6, 7.4, 8.6, 9.4, 20.6, 21.7, 23.3_
  - _Boundary: AppContextListener, ExpiryScheduler_
  - _Depends: 4.3, 7.1, 9.1, 10.3_

- [ ] 11.2 Tích hợp audit log và validation vào toàn bộ state transitions
  - Kết nối AuditService với thay đổi branch/movie/screen/showtime/ticket/payment/voucher/concession/shift/assignment; chuẩn hóa rollback khi lỗi.
  - Hoàn tất khi kiểm tra integration cho một luồng end-to-end thấy audit actor/before/after/result và lỗi business không thay đổi dữ liệu.
  - _Requirements: 13.4, 15.1, 15.2, 15.3, 15.4, 15.5_
  - _Boundary: Cross-domain integration_
  - _Depends: 2.3, 3.1, 3.2, 4.1, 4.2, 5.2, 6.2, 7.4, 8.2, 9.2, 10.1_

## 12. End-to-end validation và SQL Server concurrency verification

- [ ] 12.1 Hoàn thiện bộ integration test SQL Server cho race conditions
  - Chạy multi-thread test cho showtime overlap, seat hold/confirm, voucher lượt cuối, wallet spend, loyalty increment và F&B stock cuối cùng.
  - Hoàn tất khi mỗi scenario có đúng số giao dịch thắng theo requirement, không deadlock và dữ liệu không âm/trùng.
  - _Requirements: 4.3, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 9.7, 10.4, 11.6, 12.4, 17.6, 18.6, 20.4, 21.8_
  - _Boundary: Integration test suite_
  - _Depends: 4.2, 5.2, 5.3, 6.1, 7.4, 8.2, 9.2_

- [ ] 12.2 Chạy E2E critical paths và regression suite
  - Tự động hóa luồng Guest discovery → Customer register → hold/booking/voucher/payment → ticket validation; luồng POS/shift; luồng pre-order/pickup/cancel cascade; luồng branch-scope denial.
  - Hoàn tất khi toàn bộ test unit/integration/E2E pass và các hành vi observable trong 23 requirements không có regression.
  - _Requirements: 1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 7.1, 8.1, 9.1, 10.1, 11.1, 12.1, 13.1, 14.1, 15.1, 16.1, 17.1, 18.1, 19.1, 20.1, 21.1, 22.1, 23.1_
  - _Boundary: E2E regression suite_
  - _Depends: 11.1, 11.2, 12.1_

## Implementation Notes

- (1.2) mssql-jdbc 12.x không hỗ trợ named pipe → không kết nối LocalDB bằng JDBC được; SQL Express service cần quyền admin để start. Test DB mặc định dùng H2 in-memory (MODE=MSSQLServer) theo design "JUnit 5 + H2/mock"; integration test SQL Server thật chạy qua `-Dcinema.test.jdbcUrl`. Schema T-SQL vẫn verify trên LocalDB bằng sqlcmd (task 1.3).
- (1.2) H2 không nhận `INSERT table(...)` kiểu T-SQL — phải dùng `INSERT INTO`.
- (1.4) Typed exception thực tế là nested class `ServiceException.Validation/.Unauthorized/.Forbidden/.NotFound/.Conflict/.BusinessRule` (không phải `ValidationException`... như design.md phác thảo); task sau dùng tên thực tế. `AuditService` nhúng INSERT trực tiếp, không có `AuditDAO` riêng.
