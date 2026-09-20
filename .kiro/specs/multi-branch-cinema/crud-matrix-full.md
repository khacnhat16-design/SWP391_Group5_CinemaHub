# CRUD Matrix — CinemaHub Multi-Branch Management (Code Audit)

**Project:** `cinema-management` (Java Servlet + JSP + SQL Server + vanilla JS)
**Audit Date:** 2026-09-17
**Scope:** Tất cả module CRUD thực tế trong codebase (Servlet, Filter, DAO, Service, JSP, SQL)
**Roles audited:** ADMIN, BRANCH_MANAGER, BRANCH_STAFF, CUSTOMER (và Guest cho public)

> **Không thay đổi code** — chỉ scan và báo cáo.

---

## 0. Authentication & Authorization Framework (đã có, dùng xuyên suốt)

| Layer | File | Vai trò |
|---|---|---|
| `AuthFilter` | `filter/AuthFilter.java` | Đọc session → resolve `AccessScope` (role + branchIds), gắn vào request attribute `accessScope` |
| `BranchScopeRBACFilter` | `filter/BranchScopeRBACFilter.java` | First-line defense: whitelist 4 nhóm URL (public/customer/staff/admin); chặn 403 + audit log nếu sai role |
| `AccessScope` | `auth/AccessScope.java` | Permission set cố định theo role (Admin = mọi quyền, Manager/Staff/Customer = EnumSet) |
| `CsrfFilter` | `filter/CsrfFilter.java` | CSRF token cho POST |
| `EncodingFilter` | `filter/EncodingFilter.java` | UTF-8 request/response |
| `ExceptionMappingFilter` | `filter/ExceptionMappingFilter.java` | ánh xạ ServiceException → HTTP status |

**Permission Matrix (`AccessScope.permissionsFor`):**

| Permission | ADMIN | MANAGER | STAFF | CUSTOMER | GUEST |
|---|:---:|:---:|:---:|:---:|:---:|
| BRANCH_MANAGE | ✅ | | | | |
| MOVIE_MANAGE | ✅ | | | | |
| SCREEN_MANAGE | ✅ | ✅ | | | |
| SHOWTIME_MANAGE | ✅ | ✅ | | | |
| PRICING_MANAGE | ✅ | | | | |
| VOUCHER_MANAGE | ✅ | | | | |
| LOYALTY_MANAGE | ✅ | | | | |
| PRODUCT_MANAGE | ✅ | | | | |
| USER_MANAGE | ✅ | | | | |
| REPORT_VIEW | ✅ | ✅ | | | |
| AUDIT_VIEW | ✅ | | | | |
| TICKET_SELL | | ✅ | ✅ | | |
| TICKET_VALIDATE | | ✅ | ✅ | | |
| CONCESSION_SELL | | ✅ | ✅ | | |
| CONCESSION_PICKUP | | ✅ | ✅ | | |
| INVENTORY_VIEW | | ✅ | ✅ | | |
| INVENTORY_MANAGE | ✅ | ✅ | | | |
| SHIFT_OPEN/CLOSE | | ✅ | ✅ | | |
| SHIFT_APPROVE | ✅ | ✅ | | | |
| REFUND_APPROVE | | ✅ | | | |
| LOYALTY_ADJUST | | ✅ | ✅ | | |
| SHOWTIME_BROWSE | ✅ | ✅ | ✅ | ✅ | ✅ |
| BOOKING_CREATE | | | | ✅ | |
| BOOKING_VIEW_OWN | | | | ✅ | |
| BOOKING_CANCEL_OWN | | | | ✅ | |
| CONCESSION_PREORDER | | | | ✅ | |
| WALLET_USE | | | | ✅ | |
| LOYALTY_VIEW | | | | ✅ | |

> Defense-in-depth: filter kiểm tra nhóm role, controller kiểm tra `scope.can(permission)`, DAO/Service kiểm tra `scope.includesBranch()` cho staff.

---

## 1. CRUD Matrix — Đầy đủ theo Module

> ✅ = hoạt động · ⚠️ = có nhưng có vấn đề · ❌ = thiếu hoàn toàn · 🔒 = chỉ controller (thiếu UI)

### A. Core Business Modules

| # | Module | Role chính | List | View | Create | Edit | Delete | Search | Filter | Sort | Pagination | Status mgmt | Approve/Reject | Assign | Authz | Validation | Branch Scope |
|---|---|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:-:|:-:|:-:|
| 1 | **User** (account nội bộ + customer) | ADMIN | ✅ | ✅ | ✅ | ✅ | ✅ soft | ✅ `q` | ✅ `role` | ❌ | ⚠️ `page+pageSize` | ✅ ACTIVE/LOCKED/INACTIVE | ❌ | ❌ | ✅ requireAdmin | ✅ ServiceException.Validation | n/a (Admin) |
| 2 | **Branch** (Cinemas) | ADMIN | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ `q` | ✅ `status` | ✅ `sortBy,sortDir` | ✅ `offset+limit+total` | ✅ deactivate | ❌ | ❌ | ✅ requireAdmin | ✅ name/address/phone + check blank | n/a (Admin) |
| 3 | **Movie** (Catalog) | ADMIN | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ `q` | ✅ `status/genre/rating` | ✅ `sortBy,sortDir` | ✅ `offset+limit+total` | ✅ publish/archive | ❌ | ❌ | ✅ requireAdmin | ✅ `MovieService` validation | n/a (Admin) |
| 4 | **Screen** (Rooms) | ADMIN, MANAGER | ✅ (by branchId) | ❌ direct GET /{id} | ✅ branchId+code+name+rowCount+colCount+vipRows | ✅ rowCount/colCount/vipRows + deactivate | ❌ | ❌ | ✅ branch filter | ❌ | ❌ | ✅ deactivate | ❌ | ❌ | ✅ `scope.can("SCREEN_MANAGE")` | ✅ requireParam + rowCount/colCount>0 | ✅ `scopeBranch()` ép branch cho Manager |
| 5 | **Showtime** (Suất chiếu) | MANAGER, ADMIN | ✅ `manage/search` | ✅ `/{id}` chi tiết + seats | ✅ movieId+screenId+startTime | ✅ updateTime / cancel | ❌ | ❌ | ✅ branchId+movieId+date+status | ✅ sortBy,sortDir | ✅ offset+limit+total | ✅ cancel action | ❌ | ❌ | ✅ `scope.can("SHOWTIME_MANAGE")` | ✅ requireParam startTime format | ✅ ép branch nếu Manager có 1 branch |
| 6 | **Booking/Ticket** (khách mua online) | CUSTOMER | ✅ `mine` (/booking/mine) | ⚠️ embed trong list | ✅ hold → confirm (VNPay/MOCK/WALLET) | ❌ | ✅ cancel (own) | ❌ | ❌ status | ❌ | ⚠️ limit (TOP 50–200) | ✅ CONFIRMED → CANCELLED | n/a | n/a | ✅ CUSTOMER only, session userId only (Req 16.5 chống IDOR) | ✅ requireParam + seatIds list parse | n/a (own user only) |
| 7 | **Counter-Sale** (Staff bán vé tiền mặt) | STAFF/MANAGER | n/a | n/a | ✅ showtimeId+seatIds+voucherCode+customerId | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | ✅ `scope.can("TICKET_SELL")` | ✅ requireParam + seatIds list | ✅ ép `requireSingleBranch()` cho staff |
| 8 | **Ticket Validate** (soát vé) | STAFF/MANAGER | ✅ `booking/list` (Admin/Manager only) | implicit | n/a | n/a | n/a | n/a | n/a | n/a | ⚠️ limit | implicit (USING status update) | n/a | n/a | ✅ `scope.can("TICKET_VALIDATE")` | ✅ ticketCode required | ✅ ép branch cho staff, Admin toàn hệ |
| 9 | **F&B Product** | ADMIN | ✅ `/concession/products` | implicit | ✅ name+price+unit+type(+components cho COMBO) | ✅ price (legacy 3 fields) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ deactivate | ❌ | ❌ | ✅ `Role.ADMIN only` | ✅ requireParam name/price/type | n/a (global catalog) |
| 10 | **Concession Order (POS)** | STAFF/MANAGER | ✅ search theo status/date/product/q | ✅ `/{id}` | ✅ lines + branchId + customerId | ❌ | ✅ `cancel` (Manager) | ✅ `q` | ✅ status+date+product | ✅ sortBy,sortDir | ✅ offset+limit+total | ✅ PENDING→CONFIRMED→READY→FULFILLED, CANCELLED | n/a | customerId optional | ✅ `scope.can("CONCESSION_SELL/REFUND_APPROVE")` | ✅ parseLines / parseComboComponents | ✅ ép branch cho staff (`requireSingleBranch`) |
| 11 | **Concession Pre-order** (online) | CUSTOMER | ✅ `/concession/orders/mine` | ✅ `/{id}` (chỉ own) | ✅ ticketId + lines + payMethod | n/a | ✅ cancel PENDING | ❌ | ✅ status | ❌ | implicit | ✅ PENDING→PAID→READY→FULFILLED | n/a | n/a | ✅ CUSTOMER + ownership check | ✅ requireParam lines | n/a (own) |
| 12 | **Concession Pickup** (quét QR) | STAFF/MANAGER | scanner UI (no list) | n/a | n/a | n/a | n/a | ✅ manual code entry | n/a | n/a | n/a | ✅ READY_FOR_PICKUP→FULFILLED (idempotent) | n/a | n/a | ✅ `scope.can("CONCESSION_PICKUP")` | ✅ pickupCode required | ✅ branch check ở scan/redeem |
| 13 | **Pricing Rule** | ADMIN | ✅ | implicit | ✅ seatType+dayType+timeSlot+price | ✅ price only | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ (1 bảng cố định) | ❌ | ❌ | ✅ `Role.ADMIN only` | ⚠️ không validate enum seatType/dayType/timeSlot | n/a (global) |
| 14 | **Inventory** | ADMIN, MANAGER | ✅ `stockOfBranch(branchId)` | implicit | n/a (Adjustment) | ✅ adjust ±diff / restock + | ❌ | ❌ | ✅ branchId select | ❌ | ❌ | ⚠️ chỉ là add/subtract, không có low-stock alert | n/a | n/a | ✅ `INVENTORY_MANAGE` | ✅ productId/qty required | ✅ ép branch cho Manager, Admin mọi chi nhánh |
| 15 | **Voucher** | (chưa có Admin UI; chỉ nhúng vào BookingService) | n/a | implicit | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| 16 | **Shift** (Ca làm) | STAFF open/close, MANAGER approve, ADMIN toàn chuỗi | ✅ `history` (Manager) + `search` (paginated) + `reconciliation` (Admin) | ✅ `/{id}` summary | ✅ open: openingCash | ✅ close: actualCash+note | ✅ approve/reject | n/a | ✅ status+from+to | ✅ sortBy,sortDir | ✅ offset+limit+total | ✅ OPEN/CLOSED/APPROVED/REJECTED | ✅ approve/reject (Manager SHIFT_APPROVE) | implicit (staffId→userId) | ✅ `SHIFT_OPEN/CLOSE/APPROVE/REPORT_VIEW` | ✅ openingCash/closingCash > 0; reject cần note | ✅ ép branch cho Manager history |
| 17 | **Wallet** (khách hàng) | CUSTOMER | ✅ `history` (chỉ own) | ✅ balance | ✅ top-up: amount+method (VNPay/MOCK) | n/a | n/a | n/a | n/a | n/a | implicit | implicit (TOPUP/PAYMENT/REFUND) | n/a | n/a | ✅ CUSTOMER only, lấy userId từ session | ✅ amount > 0 | own only |
| 18 | **Wallet Admin** (xem sao kê toàn hệ) | ADMIN, MANAGER | ✅ `/admin/wallet/transactions` | n/a | n/a | n/a | n/a | n/a | n/a | n/a | ⚠️ limit TOP 200 | n/a | n/a | n/a | ✅ ADMIN/MANAGER | ⚠️ không lọc theo branch cho Manager (xem cả chain) | ❌ Manager có thể xem ví của branch khác |
| 19 | **Loyalty** (điều chỉnh điểm) | STAFF/MANAGER/ADMIN | ✅ `/loyalty/ledger` paginated | ✅ `/loyalty/info` | ✅ `/loyalty/adjust` customerId+delta+reason | n/a | n/a (delta âm thì balance giảm) | n/a | ❌ | n/a | ✅ limit+offset (max 100) | implicit (delta dương/âm) | n/a | staffId implicit | ✅ `scope.canAdjustPoints()` (BRANCH_STAFF/MANAGER/ADMIN) | ✅ reason ≥ 5 chars; |delta| ≤ 10000 | ⚠️ filter theo branch ở ledger (Manager/Staff chỉ ledger branch mình) |
| 20 | **Loyalty Customer** (read-only self) | CUSTOMER | ✅ `/loyalty/ledger/me` paginated | ✅ `/loyalty/me` + `/loyalty/info?orderTotal=` | n/a | n/a | n/a | n/a | n/a | n/a | ✅ limit+offset (max 100) | n/a | n/a | n/a | ✅ CUSTOMER only | implicit | own only |
| 21 | **Notification** | CUSTOMER | ✅ `listForUser(unreadOnly?)` | implicit (`/{id}/read`) | implicit (system-creates khi booking/loyalty) | ✅ markRead `{id}` | n/a | n/a | ✅ `unreadOnly` | n/a | n/a (chưa phân trang) | ✅ read/unread | n/a | n/a | ✅ CUSTOMER only, ownership check ở markRead | ✅ guard owner trong `markRead` | own only |
| 22 | **Report** (doanh thu/vận hành) | ADMIN, MANAGER | ✅ `/revenue`+`/daily`+`/movies`+`/operations`+`/summary` | n/a | n/a | n/a | n/a | n/a | ✅ from+to+branchId+movieId | ⚠️ page+pageSize chỉ 2 trong 5 | n/a | n/a | n/a | n/a | ✅ `REPORT_VIEW` | ✅ date parse format | ✅ ép `requireSingleBranch` cho Manager |
| 23 | **Discovery** (công khai) | GUEST+ | ✅ `/discover` + `/api/showtimes/discovery` | implicit | n/a | n/a | n/a | n/a | ✅ movieId+branchId+date | n/a | n/a | implicit (OPEN only) | n/a | n/a | ✅ public (GUEST_PREFIXES whitelist) | date parse | n/a (public) |
| 24 | **Seat Hold** (lock 10p) | CUSTOMER | n/a | n/a | ✅ POST `/booking/hold` showtimeId+seatIds | n/a | ✅ auto-release on timeout | n/a | n/a | n/a | n/a | ✅ HELD→CONFIRMED/RELEASED | n/a | n/a | ✅ CUSTOMER only + active account check | ✅ seatIds list | n/a |
| 25 | **Upload** (poster ảnh) | ADMIN, MANAGER | n/a | n/a | ✅ POST `/upload` multipart `file` | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | ✅ ADMIN or MANAGER | ✅ MIME whitelist (jpeg/png/webp) + extension + magic-byte sniff + maxFileSize | n/a |

---

## 2. Phân tích theo câu hỏi của Task

### 2.1 CRUD nào đang HOẠT ĐỘNG (✅)

- **User** (Admin): List + Search + Filter + Role + Create + Update + Status Toggle + Soft-delete
- **Branch**: List (active vs all) + Search/sort/paginate + Create + Edit + Deactivate
- **Movie**: List (admin) + Search/sort/paginate + Create + Update + Publish/Archive + Upload poster
- **Screen** (Admin/Manager): List by branch + Create (with rowCount/colCount/vipRows) + Edit seat map + Deactivate
- **Showtime** (Admin/Manager): List (manage/search with sort/paginate) + View detail + Create + UpdateTime + Cancel
- **Booking** (Customer): List mine + Hold + Release + Confirm (VNPay/MOCK/Wallet) + Counter-sale (Staff) + Cancel + Validate
- **Ticket list** (Admin/Manager): List với branch scope ép
- **F&B Product** (Admin): List + Create (incl. COMBO) + Update price/name/type + Deactivate
- **Concession Order**: Staff list (search/filter/sort/paginate) + View detail + Create (POS / pre-order / standalone) + Pay + Cancel + Scan + Redeem (idempotent)
- **Pricing Rule** (Admin): List + Create + Update + Delete
- **Inventory** (Admin/Manager): List stock by branch + Restock + Adjust (+reason)
- **Shift**: Open (Staff) + Close (Staff) + Approve/Reject (Manager) + History + Reconciliation (Admin)
- **Wallet** (Customer): Show balance + History + Top-up (VNPay/Mock)
- **Wallet Admin** (Admin/Manager): Show all transactions
- **Loyalty Adjust** (Staff/Manager): POST adjust with reason
- **Loyalty Ledger** (Staff/Manager paginated, Customer own)
- **Notification** (Customer): List + MarkRead single + Read-all (ownership guarded)
- **Report** (Admin/Manager): revenue / daily / movies / operations / summary (date filter)
- **Discovery** (Guest): Find showtimes by branch/movie/date
- **Seat Hold** (Customer): Hold seats 10min
- **Upload** (Admin/Manager): Upload image (with server-side validation)

### 2.2 CRUD nào đang LỖI hoặc có CAVẾT cần fix

| Module | Vấn đề | Bằng chứng |
|---|---|---|
| **Shift `/history`** | try/catch swallows toàn bộ exception khi `history()`, trả `[]` — che lỗi thật (e.g. DB down). Admin thấy "rỗng" thay vì lỗi. | `ShiftController.java:90-98` |
| **Ticket list `/booking/list`** | cứng `TOP 50–200`; không có offset/pagination thật cho Admin xem cả hệ thống — sẽ trả N-1 rows | `BookingController.java:135-179` |
| **Wallet Admin** | Manager xem được transaction của **mọi branch** (`/admin/wallet/transactions`); thiếu branch scope ép | `WalletAdminServlet.java:40-47` (không ép branch) |
| **Pricing rules** | Không check enum: seatType/dayType/timeSlot client có thể gửi garbage, chỉ service validate nhẹ | `PricingController.java:50-60`; `PricingService.createRule()` |
| **Screen update** | `rowCount/colCount` mới nhưng không có cơ chế migration seats đã sinh từ layout cũ → có thể orphan Seat rows | `ScreenController.java:115-118` (`updateSeatMap`) |
| **Showtime create** | Không check trùng giờ showtime cùng screen (qua DAO) — chỉ branch filter | `ShowtimeService.create()` |
| **Loyalty adjust** | Manager/Staff không bị giới hạn customerId phải thuộc branch mình — chỉ ledger branch-scoped, còn balance không | `StaffLoyaltyController.java:280-300` |
| **Voucher** | Chỉ là DAO/Service nội bộ — KHÔNG có admin CRUD UI hay controller | `web/` không có VoucherServlet |
| **Upload** | Mỗi upload tự động overwrite `site_setting.home_banner_url` — side-effect không mong muốn | `UploadServlet.java:120-125` |
| **Booking `/mine`** | Không phân trang — trả full list | `BookingController.java:418-470` |

### 2.3 CRUD nào THIẾU (❌ module chưa có)

| Module | Thiếu gì | Ghi chú |
|---|---|---|
| **Voucher Admin** | CRUD controller | chỉ internal helper |
| **User — reset password (admin force)** | chỉ `/forgot-password` self-service; admin chưa reset được từ UI | `AdminUsersServlet.doPut` chỉ update name/phone/status |
| **Branch delete** | chỉ deactivate, không xóa cứng | `BranchController` thiếu DELETE |
| **Movie delete** | chỉ archive | `MovieController` thiếu DELETE |
| **Screen delete** | chỉ deactivate | `ScreenController` thiếu DELETE |
| **Showtime delete** | chỉ cancel | `ShowtimeController` thiếu DELETE |
| **Notification delete** | không có DELETE (markRead chỉ là soft) | `NotificationController` |
| **Loyalty Customer list** (Staff search khách) | chỉ search bằng exact ID, không có customer-search-by-name | audit report nói rõ |
| **Promotion** | project chưa có module Promotion (chỉ Voucher) | cần confirm |
| **Reconciliation approval workflow** | Manager xem được reconciliation nhưng không có action "approve discrepancy" | `ShiftController.history()` |

### 2.4 CRUD nào đang dùng MODAL cho Create/Edit (UI)

Audit kết hợp với `crud-audit-report.md` (đã có):

- ✅ **Branch** (modal create + edit)
- ✅ **Movie** (modal create + edit + upload poster)
- ✅ **Screen** (modal create + edit)
- ✅ **Product** (modal create + edit price)
- ✅ **Pricing** (modal create + edit + delete)
- ✅ **Inventory** (modal stock-in + adjust)
- 🔒 **User** (chỉ read-only table — modal chưa có)
- 🔒 **Showtime** (chỉ read-only table — modal chưa có)
- ✅ **Shift approve** (qua modal note)
- ✅ **Customer Points** (modal adjust)

### 2.5 CRUD nào có UI/CSS xấu hoặc không đồng nhất

> Phần lớn đã audit trong `crud-audit-report.md` (đã có). Tổng hợp chính:

- 🟢 Tốt: F&B staff orders, F&B customer catalog → cart → orders, workspace.css design system
- 🔴 Chưa đồng nhất / thiếu modal: User, Showtime, Shift
- 🟡 Trộn 2 hệ thống class: `cinema.css` (form cũ, full-width button) ↔ `workspace.css` (60+ ws-* utility, rounded cards)
- Một số page standalone JSP dùng cấu trúc card cũ (vd: `report/list.jsp`) trong khi console dùng `rest-page.js` (`pages/report` qua `console.js MODULES`)

### 2.6 CRUD nào THIẾU Authorization

| Endpoint | Vấn đề | Mức độ |
|---|---|---|
| `/admin/wallet/transactions` | Manager xem được tất cả branch (thiếu branch filter) | 🟠 |
| `/api/users` POST/PUT/DELETE | Chỉ check ADMIN — KHÔNG audit-log + KHÔNG chặn self-delete hoặc self-demote | 🟠 |
| `/api/users/{id}/status` | Admin có thể LOCK tài khoản chính mình | 🟠 |
| `/api/dashboard`, `/api/notification` | filter `STAFF_PREFIXES` chấp nhận CUSTOMER too (vì set chung customer) | 🟢 false-positive (filter chỉ chặn GUEST) |
| `/api/concession/*` | filter `STAFF_PREFIXES` + `CUSTOMER_PREFIXES` — overlap — cần verify Controller có chặn cross-role | 🟢 Controller đã chặn |
| `StaffLoyaltyController.adjust` | Manager/Staff có thể adjust customer ở branch khác (chỉ ledger branch filter, balance unbounded) | 🟠 |
| `/upload` | Admin/Manager đều được (OK); nhưng KHÔNG kiểm tra CSRF token | 🟡 (CsrfFilter có nhưng tùy endpoint config) |
| `MovieController.doGet` | BranchManager/Staff xem được `/{id}` (full movie); còn Admin xem được of mọi status — không lộ scope cho Customer | 🟢 đã chặn |
| `/booking/cancel` | Manager cancel được vé ở branch khác (controller gọi `cancelTicket(actorStaffBranchId)` với branch ép từ scope, OK; NHƯNG filter `STAFF_PREFIXES` chấp nhận mọi Staff role vào — controller check `TICKET_SELL` chỉ — đủ) | 🟢 |
| `/api/pricing/rules` GET | `Role.ADMIN only` — Staff/Manager không xem giá → OK | 🟢 |

### 2.7 CRUD nào có vấn đề BRANCH SCOPE

| Vấn đề | Bằng chứng | Manager/Staff có bị giới hạn? |
|---|---|---|
| Manager xem `mọi chi nhánh` wallet transactions | `WalletAdminServlet.java:40` không ép branch | ❌ |
| `BranchScopeRBACFilter.STAFF_PREFIXES` rất rộng — staff/manager cùng được, controller check `can()` sau | Filter không phân biệt Staff vs Manager | ⚠️ Controller check là nguồn sự thật |
| `StaffLoyaltyController` adjust không giới hạn customerId theo branch | `staffId+branchId` ghi log, balance unbounded | ❌ |
| `/api/booking` (đang dùng) | branch scope ép chỉ trong `/booking/list` — OK | ✅ |
| `Concession.scan/redeem` | đã check `order.branchId() != staffBranchId` | ✅ |
| `Shift.history` | ép branch cho Manager qua `requireSingleBranch` | ✅ |
| `Inventory` | ép branch cho Manager | ✅ |
| `Report` | ép branch cho Manager | ✅ |
| `Pricing rules` | global, Admin only — OK | ✅ |
| `Movie catalog` | Branch Manager/Staff chỉ xem PUBLISHED trong window | ✅ |
| `Showtime manage/search` | nếu scope có 1 branch, ép `branchId = single branch` | ✅ |
| `BranchScopeRBACFilter.CUSTOMER_PREFIXES` chứa cả `/branch` — Customer có thể truy cập `/api/branch/...` để list active → intentional (list active) | OK | ✅ |

### 2.8 CRUD nào có vấn đề VALIDATION

| Module | Validation thiếu/sai |
|---|---|
| `PricingController.createRule` | không validate seatType/dayType/timeSlot enum; giá có thể âm nếu service không check |
| `BranchController.update` | không validate unique name (chỉ service) |
| `User create` | password tối thiểu (server-side? chưa thấy explicit) |
| `UserDAO.create` | không hash password rõ ràng ở controller (service có thể OK) |
| `Showtime create` | không validate startTime không ở quá khứ |
| `Booking.hold` | chỉ parse seatIds string ("1,2,3"); không kiểm tra `quantity <= MAX_SEATS_PER_BOOKING` |
| `Loyalty.adjust` | ✅ reason ≥ 5, |delta| ≤ 10000, customer existence |
| `Wallet top-up` | ✅ amount > 0 (parse fail → 400) |
| `Concession.createOrder` | ✅ lines non-empty, parseComboComponents format |
| `Upload` | ✅ MIME+ext+magic-byte+maxFileSize |

### 2.9 CRUD nào có vấn đề PERFORMANCE

| Endpoint | Vấn đề | Ghi chứng |
|---|---|---|
| `BookingController.ticketsForUser` (đã fix N+1 — chú thích trong code) | OK đã dùng batch `findSeatsByTickets` | ✅ |
| `BookingController.listAllTickets` (Admin/Manager) | cứng `TOP (?)`; không có offset; không có WHERE branch filter ép trong SQL (chỉ chèn điều kiện) | 🟠 |
| `ConcessionController.handleScan` | load `user.findById` + `order.lines` mỗi lần scan — không cache | 🟡 |
| `WalletAdminServlet` | không có index gợi ý, không phân trang | 🟠 |
| `NotificationController` | không phân trang | 🟡 |
| `Loyalty.findLedgerPaginated` | OK đã paginate | ✅ |
| `Discovery` | gọi `findAllActive` mỗi request — không cache | 🟡 |
| `Shift.history` / `reconcile` | tính lại toàn bộ range mỗi request | 🟡 |
| `Report.operations` | gọi `auditService.findUnauthorized` không có index — có thể chậm | 🟡 |
| `MovieController` `listAll` cho Admin dùng `movieService.listAll()` trong khi `search`/`listSelectableForScheduling` đã phân trang | 🟡 inconsistency |

### 2.10 Module chưa có (theo gợi ý của task)

- **Promotion**: project chưa có module riêng (chỉ `Voucher` làm giảm giá). Không tự tạo theo audit scope.
- **Membership**: nằm trong `Loyalty` (tier: BRONZE/SILVER/GOLD/PLATINUM) — không tách riêng.
- **Cinema/Room**: `Screen` chính là "room" (phòng chiếu).
- **Seat**: nằm trong Screen CRUD (`ScreenService.updateSeatMap` tạo seats).

---

## 3. Servlet Inventory (factual)

Tổng **28 servlet controller + 6 filter + 1 application entry**, phân loại:

**Auth/Public (6):**
- `AuthController`, `ForgotPasswordServlet`, `CinemaServlet` (page routing), `PageController`, `DiscoveryServlet`, `VnPayController`, `PaymentController`

**Admin / Manager (8):**
- `AdminUsersServlet`, `BranchController`, `MovieController`, `PricingController`, `ReportController`, `WalletAdminServlet`, `UploadServlet`, `WorkspaceNotificationServlet`

**Branch Manager / Staff (6):**
- `ScreenController`, `ShowtimeController`, `ShiftController`, `InventoryController`, `ConcessionController`, `BookingController` (ngoài `counter-sale`/`validate` còn `cancel`/`list` admin-manage)

**Customer (4):**
- `WalletController`, `StaffLoyaltyController` (cùng dùng cho customer endpoints `/loyalty/me`, `/loyalty/info`), `NotificationController`, `ProfileController`, `SessionController`

**Filters (6):**
- `AuthFilter`, `BranchScopeRBACFilter`, `CsrfFilter`, `EncodingFilter`, `ExceptionMappingFilter`, `NoCacheFilter`

**DAO/Service xem trong `src/main/java/com/cinema/<domain>/`** — đếm nhanh:
- auth (5), branch, booking, concession (3+), inventory, loyalty, movie, notification, payment (4+), pricing, report, screen (3), shift (3), showtime (3), upload (2), voucher (3), wallet (2), site, audit, common, util, dal

---

## 4. JSP/Servlet Endpoints (complete)

### 4.1 JSP Pages (present)

| File | Mục đích | Render cơ chế |
|---|---|---|
| `console.jsp` | workspace console shell | delegates to `console.js` MODULES |
| `auth/login.jsp`, `register.jsp`, `forgot-password.jsp`, `forgot-password-verify.jsp`, `forgot-password-reset.jsp` | auth pages | static form + CSRF |
| `layout/workspace-header.jsp`, `workspace-sidebar.jsp`, `header.jsp`, `sidebar.jsp`, `footer.jsp`, `appshell-open.jsp`, `appshell-close.jsp` | shared layout fragments | <%@ include %> |
| `movie/list.jsp` | standalone movie page (used by `PageController`) | rest-page.js via meta tag |
| `branch/list.jsp` | standalone branch page | rest-page.js |
| `screen/list.jsp` | standalone screen page | rest-page.js |
| `booking/list.jsp`, `booking/guest-notice.jsp` | booking + guest notice | rest-page.js / static |
| `concession/list.jsp` | standalone concession | rest-page.js |
| `report/list.jsp` | standalone report | rest-page.js |
| `shift/list.jsp` | standalone shift | rest-page.js |
| `notification/list.jsp` | standalone notification | rest-page.js |
| `payment/vnpay-demo.jsp` | VNPay mock gateway UI | static |

> Modules đã có trong `console.js MODULES` (renderIn shell): `dashboard, profile, users, wallet, discovery, seat, validate, concession-orders, concession-pickup, customer-points, fnb-catalog, fnb-cart, fnb-orders, pos-ticket, pos-concession, shift, ticket, pickup`.

### 4.2 Servlet + Endpoint Map (chronological — for security review)

| URL pattern | Servlet | Verbs | Permission guard |
|---|---|---|---|
| `/api/users[/{id}[/status]]` | `AdminUsersServlet` | GET/POST/PUT/DELETE | `Role.ADMIN` |
| `/admin/login`, `/login`, `/register`, `/logout` | `AuthController` | GET/POST | public |
| `/forgot-password*` | `ForgotPasswordServlet` | GET/POST | public |
| `/booking/{hold,release,confirm,cancel,validate,counter-sale}` | `BookingController` | POST | role+can(perm) |
| `/booking/{mine,list}` | `BookingController` | GET | CUSTOMER / (ADMIN,MANAGER) |
| `/branch[/{id}]`, `/branch/search`, `/branch/active` | `BranchController` | GET/POST/PUT | ADMIN (CUSTOMER chỉ active) |
| `/cinema/*` | `CinemaServlet` | GET | mixed (mostly public) |
| `/movie[/{id}]`, `/movie/search` | `MovieController` | GET/POST/PUT | ADMIN write, read split |
| `/screen[/{id}/seats]` | `ScreenController` | GET/POST/PUT | `SCREEN_MANAGE` (Admin/Manager) |
| `/showtime/{discovery,/{id},/manage/search}` | `ShowtimeController` | GET/POST/PUT | public discovery; manage = Admin/Manager |
| `/inventory` | `InventoryController` | GET/POST | `INVENTORY_VIEW`/`INVENTORY_MANAGE` |
| `/pricing/rules[/{id}]` | `PricingController` | GET/POST/PUT/DELETE | ADMIN |
| `/shift/{open,close,approve,reject,current,history,history/search,pending,/{id}}` | `ShiftController` | GET/POST | SHIFT_OPEN/CLOSE/APPROVE/REPORT_VIEW |
| `/concession/{products,orders[/{id}],/orders/mine,/order,/order/{id}/pay-vnpay,/order/{id}/payment-status,/order/pay,/order/cancel,/pickup,/scan,/redeem}` | `ConcessionController` | GET/POST/PUT | role+can(); staff enforced branch |
| `/loyalty/{me,ledger/me,info,ledger,adjust}` | `StaffLoyaltyController` | GET/POST | canAdjustPoints or CUSTOMER |
| `/wallet`, `/wallet/top-up` | `WalletController` | GET/POST | CUSTOMER |
| `/admin/wallet/transactions` | `WalletAdminServlet` | GET | ADMIN/MANAGER (no branch scope) |
| `/notification[/{id}/read]`, `/notification/read-all` | `NotificationController` | GET/POST | CUSTOMER |
| `/report/{revenue,daily,movies,operations,summary}` | `ReportController` | GET | REPORT_VIEW |
| `/upload` | `UploadServlet` | POST | ADMIN/MANAGER |
| `/payment/mock-gateway`, `/vnpay/return`, `/vnpay/demo` | Payment controllers | GET/POST | mixed (mostly public) |
| `/workspace/notification` | `WorkspaceNotificationServlet` | GET | (workspace; route check needed) |
| `/discover` | `DiscoveryServlet` | GET | public |
| `/console` | `PageController` → `console.jsp` | GET | branch-scope filter applies but `/console` HTML is whitelisted |

---

## 5. Tóm tắt các Gap quan trọng nhất

1. **Voucher module** — không có admin controller/UI; chỉ là internal DAO.
2. **User — admin self-protection** — Admin có thể tự khóa/xóa chính mình; password reset forced chưa có.
3. **Wallet Admin branch scope** — Manager xem transaction toàn chain.
4. **Loyalty adjust branch scope** — balance adjustment không bị ép theo branch.
5. **Upload auto-side-effect** — mỗi upload overwrite banner → bug-prone.
6. **Showtime conflict check** — không check trùng cùng screen/cùng giờ.
7. **Shift history silent error** — try/catch nuốt exception, trả `[]` thay vì 5xx.
8. **Pricing rule enum validation** — thiếu whitelist cho seatType/dayType/timeSlot.
9. **Branch/Movie/Screen/Showtime hard-delete** — chỉ soft (deactivate/archive/cancel), không có hard-delete API.
10. **Notification pagination** — không phân trang, danh sách có thể phình.
11. **CSRF** — `CsrfFilter` exists, cần verify áp dụng cho mọi POST (đặc biệt `/upload`, `/api/users` POST).
12. **Audit log gap** — chỉ `BranchScopeRBACFilter.auditUnauthorizedAccess` và điểm Loyalty staff; thiếu cho Create/Update/Delete trên các entity quan trọng.

---

**Báo cáo kết thúc. Không thay đổi code, không tạo module mới, không rewrite architecture.**
