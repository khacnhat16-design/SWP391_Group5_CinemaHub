const fs = require('fs');

const designPath = 'design.md';
let design = fs.readFileSync(designPath, 'utf8');

// Align the architecture diagram and source layout with the account/RBAC model.
design = design.replace(
  '        AuthC[AuthController]\n        BranchC[BranchController]',
  '        AuthC[AuthController]\n        RbacC[UserAccessController]\n        BranchC[BranchController]'
);
design = design.replace(
  '        BranchDAO\n        MovieDAO',
  '        UserAccountDAO\n        RoleDAO\n        StaffBranchAssignmentDAO\n        CustomerProfileDAO\n        BranchDAO\n        MovieDAO'
);
design = design.replace(
  '│   │   ├── UserDAO.java\n│   │   └── User.java',
  '│   │   ├── UserDAO.java                 # Tài khoản dùng chung cho Admin/Manager/Staff/Customer\n│   │   ├── RoleDAO.java                 # Danh mục 4 role được lưu; Guest không có tài khoản\n│   │   ├── StaffBranchAssignmentDAO.java # Phạm vi branch hiệu lực cho Manager/Staff\n│   │   └── User.java / Role.java / StaffBranchAssignment.java / CustomerProfile.java'
);

// Strengthen the component summary and filter contract.
design = design.replace(
  '| AuthFilter + AuthService | Filter/Service | Xác thực session, đăng nhập/đăng ký, giới hạn sai mật khẩu | 13.2, 16.1–16.4 | UserDAO (P0), DBUtil (P0) | Service |',
  '| AuthFilter + AuthService | Filter/Service | Xác thực tài khoản dùng chung, đăng ký Customer, giới hạn sai mật khẩu | 13.2, 16.1–16.4 | UserDAO/RoleDAO/CustomerProfileDAO (P0), DBUtil (P0) | Service |\n| AccessAssignmentService | Service | Gán role và branch scope hiệu lực cho Branch Manager/Branch Staff | 13.1, 13.4–13.6 | UserDAO, RoleDAO, StaffBranchAssignmentDAO (P0) | Service, API |'
);
design = design.replace(
  '| Dependencies | Inbound: EncodingFilter — thứ tự chain (P0). Outbound: UserDAO — tra user/role/scope (P0) |',
  '| Dependencies | Inbound: EncodingFilter — thứ tự chain (P0). Outbound: UserDAO/RoleDAO/StaffBranchAssignmentDAO — tra tài khoản, role và scope hiệu lực (P0) |'
);
design = design.replace(
  '- `AuthFilter.doFilter`: nếu URL yêu cầu auth mà thiếu session → redirect `/auth/login` hoặc 401 cho AJAX; kiểm tra khóa tạm thời 5 lần sai/15 phút.\n- `BranchScopeRBACFilter.doFilter`: đọc `session.user.role` + `branchId` → gắn `request.attribute("branchScope")`; chặn nếu thao tác vượt scope; ghi audit cho truy cập trái phép.',
  '- `AuthFilter.doFilter`: Guest không có `user_account`; nếu URL yêu cầu xác thực mà thiếu session thì redirect `/auth/login` hoặc trả 401 cho AJAX. Session chỉ lưu `userId`, role và active branch scopes được tra lại theo mỗi request; kiểm tra khóa tạm thời 5 lần sai/15 phút.\n- `BranchScopeRBACFilter.doFilter`: đọc role của `user_account` và các `staff_branch_assignment` đang hiệu lực. Admin có scope toàn chuỗi; Branch Manager/Branch Staff chỉ có các branch được gán; Customer chỉ có dữ liệu sở hữu. Filter gắn `request.attribute("branchScopes")`, chặn thao tác vượt scope và audit truy cập trái phép.'
);

const authSection = `\n### AuthService & AccessAssignmentService\n\n| Field | Detail |\n|-------|--------|\n| Intent | Cung cấp một danh tính xác thực cho mọi người dùng đăng nhập và quản lý role/phạm vi chi nhánh có hiệu lực |\n| Requirements | 13.1–13.6, 16.1–16.7 |\n| Dependencies | Outbound: UserDAO, RoleDAO, CustomerProfileDAO, StaffBranchAssignmentDAO (P0) |\n\n**Responsibilities & Constraints**\n- Guest là actor ẩn danh, không có hàng dữ liệu hay session xác thực.\n- `user_account` là nguồn xác thực duy nhất cho Admin, Branch Manager, Branch Staff và Customer; mỗi tài khoản có đúng một role đang hiệu lực trong phạm vi spec.\n- Khi đăng ký, AuthService tạo đồng thời `user_account` với role `CUSTOMER` và `customer_profile` cùng một transaction.\n- AccessAssignmentService chỉ cho phép Admin tạo/đóng assignment cho role `BRANCH_MANAGER` và `BRANCH_STAFF`; mọi thay đổi có hiệu lực với request kế tiếp vì filter tra assignment đang hiệu lực thay vì tin dữ liệu scope cache trong session.\n- Một Branch Manager/Branch Staff phải có ít nhất một assignment active trước khi vận hành dữ liệu branch; Customer và Admin không có staff assignment.\n\n**Contracts**: Service [x] / API [x] / State [x]\n\n##### Service Interface\n\`\`\`java\ninterface AuthService {\n  AuthenticatedUser registerCustomer(CustomerRegistrationInput input) throws ValidationException, ConflictException;\n  AuthenticatedUser login(LoginInput input) throws AuthenticationException, AccountLockedException;\n  void updateCustomerProfile(long userId, CustomerProfileInput input) throws ValidationException, ConflictException;\n}\ninterface AccessAssignmentService {\n  StaffBranchAssignment assign(long userId, long branchId, long actorAdminId) throws ValidationException, ConflictException;\n  void endAssignment(long assignmentId, long actorAdminId) throws ConflictException;\n  AccessScope resolveCurrentScope(long userId);\n}\n\`\`\`\n- Preconditions: email/phone unique across `user_account`; assignment role phải là `BRANCH_MANAGER` hoặc `BRANCH_STAFF`; branch phải ACTIVE.\n- Postconditions: session có `userId` và role sau login; profile Customer tồn tại 1:1; assignment mới/cập nhật làm request tiếp theo áp dụng scope mới.\n- Invariants: Admin có role `ADMIN`; Customer chỉ xem/sửa dữ liệu của chính mình; Guest không đi qua protected endpoint.\n\n##### API Contract\n| Method | Endpoint | Request | Response | Errors |\n|--------|----------|---------|----------|--------|\n| POST | /auth/register | CustomerRegistrationForm | session + redirect | 400, 409 |\n| POST | /auth/login | LoginForm | session + role landing page | 401, 423 |\n| POST | /access/assignment/create | userId, branchId | assignment active | 400, 403, 409 |\n| POST | /access/assignment/end | assignmentId | assignment inactive | 403, 409 |\n\n`;
design = design.replace('\n### BranchService\n', authSection + '### BranchService\n');

// Replace the old Customer-centered schema lines with one account model and rewrite all relevant FKs.
design = design.replace(
  "seat_hold(id BIGINT IDENTITY PRIMARY KEY, showtime_id BIGINT NOT NULL FOREIGN KEY REFERENCES showtime(id), user_id BIGINT NULL, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), expires_at DATETIME2(3) NOT NULL);",
  "seat_hold(id BIGINT IDENTITY PRIMARY KEY, showtime_id BIGINT NOT NULL FOREIGN KEY REFERENCES showtime(id), user_id BIGINT NULL FOREIGN KEY REFERENCES user_account(id), created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), expires_at DATETIME2(3) NOT NULL);"
);
design = design.replace(
  "ticket(id BIGINT IDENTITY PRIMARY KEY, ticket_code VARCHAR(32) NOT NULL UNIQUE, showtime_id BIGINT NOT NULL FOREIGN KEY REFERENCES showtime(id), branch_id BIGINT NOT NULL FOREIGN KEY REFERENCES branch(id), user_id BIGINT NULL, status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','USED')), total_amount BIGINT NOT NULL CHECK (total_amount >= 0), voucher_code VARCHAR(50) NULL, refund_amount BIGINT NULL, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), confirmed_at DATETIME2(3) NULL, cancelled_at DATETIME2(3) NULL, used_at DATETIME2(3) NULL, version INT NOT NULL DEFAULT 0);",
  "ticket(id BIGINT IDENTITY PRIMARY KEY, ticket_code VARCHAR(32) NOT NULL UNIQUE, showtime_id BIGINT NOT NULL FOREIGN KEY REFERENCES showtime(id), branch_id BIGINT NOT NULL FOREIGN KEY REFERENCES branch(id), user_id BIGINT NULL FOREIGN KEY REFERENCES user_account(id), status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','USED')), total_amount BIGINT NOT NULL CHECK (total_amount >= 0), voucher_code VARCHAR(50) NULL, refund_amount BIGINT NULL, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), confirmed_at DATETIME2(3) NULL, cancelled_at DATETIME2(3) NULL, used_at DATETIME2(3) NULL, version INT NOT NULL DEFAULT 0);"
);
const oldIdentity = "customer(id BIGINT IDENTITY PRIMARY KEY, email NVARCHAR(100) NOT NULL UNIQUE, phone VARCHAR(20) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, full_name NVARCHAR(100) NOT NULL, points INT NOT NULL DEFAULT 0, tier NVARCHAR(50) NOT NULL DEFAULT N'STANDARD', status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','LOCKED')), version INT NOT NULL DEFAULT 0, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME());\nwallet(user_id BIGINT PRIMARY KEY FOREIGN KEY REFERENCES customer(id), balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0), version INT NOT NULL DEFAULT 0);\nwallet_tx(id BIGINT IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL FOREIGN KEY REFERENCES customer(id), type VARCHAR(20) NOT NULL CHECK (type IN ('TOPUP','SPEND','REFUND')), amount BIGINT NOT NULL CHECK (amount > 0), balance_after BIGINT NOT NULL CHECK (balance_after >= 0), ref_type VARCHAR(50) NULL, ref_id BIGINT NULL, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME());\nloyalty_config(id BIGINT IDENTITY PRIMARY KEY, tier_name NVARCHAR(50) NOT NULL UNIQUE, min_points INT NOT NULL CHECK (min_points >= 0), discount_percent INT NOT NULL CHECK (discount_percent BETWEEN 0 AND 100));\npoint_ledger(id BIGINT IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL FOREIGN KEY REFERENCES customer(id), delta INT NOT NULL, balance_after INT NOT NULL, ref_type VARCHAR(50) NULL, ref_id BIGINT NULL, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME());";
const newIdentity = "role(code VARCHAR(30) PRIMARY KEY CHECK (code IN ('ADMIN','BRANCH_MANAGER','BRANCH_STAFF','CUSTOMER')), display_name NVARCHAR(50) NOT NULL UNIQUE);\nuser_account(id BIGINT IDENTITY PRIMARY KEY, email NVARCHAR(100) NOT NULL UNIQUE, phone VARCHAR(20) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, full_name NVARCHAR(100) NOT NULL, role_code VARCHAR(30) NOT NULL FOREIGN KEY REFERENCES role(code), status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','LOCKED','INACTIVE')), failed_login_count INT NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0), locked_until DATETIME2(3) NULL, last_login_at DATETIME2(3) NULL, version INT NOT NULL DEFAULT 0, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), updated_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME());\ncustomer_profile(user_id BIGINT PRIMARY KEY FOREIGN KEY REFERENCES user_account(id), points INT NOT NULL DEFAULT 0, tier NVARCHAR(50) NOT NULL DEFAULT N'STANDARD');\nstaff_branch_assignment(id BIGINT IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL FOREIGN KEY REFERENCES user_account(id), branch_id BIGINT NOT NULL FOREIGN KEY REFERENCES branch(id), effective_from DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), effective_to DATETIME2(3) NULL, status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','ENDED')), assigned_by BIGINT NOT NULL FOREIGN KEY REFERENCES user_account(id), created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(), CONSTRAINT UQ_staff_branch_effective UNIQUE(user_id, branch_id, effective_from));\nwallet(user_id BIGINT PRIMARY KEY FOREIGN KEY REFERENCES customer_profile(user_id), balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0), version INT NOT NULL DEFAULT 0);\nwallet_tx(id BIGINT IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL FOREIGN KEY REFERENCES customer_profile(user_id), type VARCHAR(20) NOT NULL CHECK (type IN ('TOPUP','SPEND','REFUND')), amount BIGINT NOT NULL CHECK (amount > 0), balance_after BIGINT NOT NULL CHECK (balance_after >= 0), ref_type VARCHAR(50) NULL, ref_id BIGINT NULL, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME());\nloyalty_config(id BIGINT IDENTITY PRIMARY KEY, tier_name NVARCHAR(50) NOT NULL UNIQUE, min_points INT NOT NULL CHECK (min_points >= 0), discount_percent INT NOT NULL CHECK (discount_percent BETWEEN 0 AND 100));\npoint_ledger(id BIGINT IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL FOREIGN KEY REFERENCES customer_profile(user_id), delta INT NOT NULL, balance_after INT NOT NULL, ref_type VARCHAR(50) NULL, ref_id BIGINT NULL, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME());";
if (!design.includes(oldIdentity)) throw new Error('Expected identity block not found');
design = design.replace(oldIdentity, newIdentity);
design = design.replace(
  "concession_order(id BIGINT IDENTITY PRIMARY KEY, order_code VARCHAR(32) NOT NULL UNIQUE, branch_id BIGINT NOT NULL FOREIGN KEY REFERENCES branch(id), ticket_id BIGINT NULL FOREIGN KEY REFERENCES ticket(id), showtime_id BIGINT NULL FOREIGN KEY REFERENCES showtime(id), user_id BIGINT NULL FOREIGN KEY REFERENCES customer(id),",
  "concession_order(id BIGINT IDENTITY PRIMARY KEY, order_code VARCHAR(32) NOT NULL UNIQUE, branch_id BIGINT NOT NULL FOREIGN KEY REFERENCES branch(id), ticket_id BIGINT NULL FOREIGN KEY REFERENCES ticket(id), showtime_id BIGINT NULL FOREIGN KEY REFERENCES showtime(id), user_id BIGINT NULL FOREIGN KEY REFERENCES user_account(id),"
);
design = design.replace(
  "shift(id BIGINT IDENTITY PRIMARY KEY, branch_id BIGINT NOT NULL FOREIGN KEY REFERENCES branch(id), staff_id BIGINT NOT NULL, status",
  "shift(id BIGINT IDENTITY PRIMARY KEY, branch_id BIGINT NOT NULL FOREIGN KEY REFERENCES branch(id), staff_id BIGINT NOT NULL FOREIGN KEY REFERENCES user_account(id), status"
);
design = design.replace(
  "approved_by BIGINT NULL);",
  "approved_by BIGINT NULL FOREIGN KEY REFERENCES user_account(id));"
);
design = design.replace(
  "notification(id BIGINT IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL FOREIGN KEY REFERENCES customer(id),",
  "notification(id BIGINT IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL FOREIGN KEY REFERENCES user_account(id),"
);
design = design.replace(
  "audit_log(id BIGINT IDENTITY PRIMARY KEY, actor_id BIGINT NULL,",
  "audit_log(id BIGINT IDENTITY PRIMARY KEY, actor_id BIGINT NULL FOREIGN KEY REFERENCES user_account(id),"
);

// Replace data-model prose and add operational indexes/invariants.
design = design.replace(
  '- **Aggregates**: Branch, Movie, Screen (→ Seats), Showtime, Ticket+SeatHold+Payment, Voucher, Product→Combo, BranchInventory, ConcessionOrder, Customer→Wallet/Points, Shift, Notification.\n- **Invariants**: Branch inactive chặn tạo mới phụ thuộc; Showtime không giao thoa cùng Screen; SeatHold hết hạn coi như AVAILABLE; ví không âm (guarded); tồn kho không âm.',
  '- **Aggregates**: UserAccount (→ Role; CustomerProfile hoặc StaffBranchAssignment), Branch, Movie, Screen (→ Seats), Showtime, Ticket+SeatHold+Payment, Voucher, Product→Combo, BranchInventory, ConcessionOrder, CustomerProfile→Wallet/Points, Shift, Notification.\n- **Invariants**: Guest không có account; mỗi account có một role trong 4 role lưu trữ; chỉ Customer có CustomerProfile/Wallet/PointLedger; chỉ Branch Manager/Branch Staff có StaffBranchAssignment active; Admin có scope toàn chuỗi. Branch inactive chặn tạo mới phụ thuộc; Showtime không giao thoa cùng Screen; SeatHold hết hạn coi như AVAILABLE; ví không âm (guarded); tồn kho không âm.'
);
design = design.replace(
  '- Quan hệ chính: Branch 1—N Screen, Screen 1—N Seat, Showtime N—1 Movie+Screen, Showtime 1—N ShowtimeSeat status, Ticket N—1 Showtime + N—N Seat, ConcessionOrder 1—N OrderLine, Shift 1—N Payment/ConcessionOrder (gán ca).',
  '- Quan hệ chính: Role 1—N UserAccount; UserAccount 1—0..1 CustomerProfile; UserAccount 1—N StaffBranchAssignment; Branch 1—N StaffBranchAssignment; Branch 1—N Screen, Screen 1—N Seat, Showtime N—1 Movie+Screen, Showtime 1—N ShowtimeSeat status, Ticket N—1 Showtime + N—N Seat, ConcessionOrder 1—N OrderLine, Shift N—1 UserAccount (staff/approver).'
);
design = design.replace(
  '- `CREATE INDEX IX_audit_entity ON audit_log(entity_type, entity_id, created_at DESC);`: phục vụ truy vết thay đổi (Req 15.3).',
  '- `CREATE INDEX IX_audit_entity ON audit_log(entity_type, entity_id, created_at DESC);`: phục vụ truy vết thay đổi (Req 15.3).\n- `CREATE INDEX IX_staff_branch_active ON staff_branch_assignment(user_id, branch_id, status, effective_from, effective_to);`: tra scope hiệu lực ở mỗi protected request (Req 13.4–13.5).\n- `CREATE UNIQUE INDEX UX_staff_branch_one_active ON staff_branch_assignment(user_id, branch_id) WHERE status = \'ACTIVE\';`: không cho tạo trùng assignment active của cùng nhân viên tại cùng branch.'
);
design = design.replace(
  '- Cơ chế khóa hàng: Dùng table hint `WITH (UPDLOCK, HOLDLOCK, ROWLOCK)` trong câu lệnh `SELECT` bên trong JDBC transaction isolation `READ COMMITTED` hoặc `SNAPSHOT` để khóa độc quyền hàng ghế theo thứ tự `seat_id ASC`, ngăn double-booking triệt để (Req 8).',
  '- Cơ chế khóa hàng: Dùng table hint `WITH (UPDLOCK, HOLDLOCK, ROWLOCK)` trong câu lệnh `SELECT` bên trong JDBC transaction isolation `READ COMMITTED` để khóa độc quyền hàng ghế theo thứ tự `seat_id ASC`, ngăn double-booking triệt để (Req 8). Không bật `READ_COMMITTED_SNAPSHOT` cho transaction giữ ghế nếu nó làm thay đổi kỳ vọng lock; xác nhận bằng integration test trên SQL Server.'
);

fs.writeFileSync(designPath, design);

const researchPath = 'research.md';
let research = fs.readFileSync(researchPath, 'utf8');
const decision = `\n### Decision: User Account thống nhất và Staff Branch Assignment hiệu lực\n- **Context**: Task-graph sanity review phát hiện schema cũ chỉ có customer, trong khi Req 13 cần Admin, Branch Manager, Branch Staff, Customer, Guest; các khóa `shift.staff_id`, `audit_log.actor_id` và các thao tác branch scope không có thực thể user chung để tham chiếu.\n- **Alternatives Considered**:\n  1. Bốn bảng đăng nhập riêng theo role — đơn giản từng bảng nhưng trùng thông tin đăng nhập, khó audit và không thể xử lý thống nhất session.\n  2. Một `user_account` chung + profile/assignment theo role — một danh tính và khóa ngoại nhất quán.\n- **Selected Approach**: `user_account` là nguồn xác thực cho ADMIN, BRANCH_MANAGER, BRANCH_STAFF, CUSTOMER; `role` cố định danh mục role; Customer có `customer_profile` 1:1; Branch Manager/Branch Staff có một hay nhiều `staff_branch_assignment` theo thời hạn hiệu lực. Guest là actor ẩn danh và không có hàng DB.\n- **Rationale**: Đáp ứng chính xác 5 role trong requirements, cho phép Admin đổi scope có hiệu lực request tiếp theo, và làm các FK vận hành/audit hợp lệ trên SQL Server.\n- **Trade-offs**: Trong phạm vi hiện tại, mỗi account có một role; việc đa-role tương lai phải nâng cấp thành bảng join `user_role` và kích hoạt revalidation Req 13, filter và permission matrix.\n- **Follow-up**: Seed ít nhất một Admin, một Manager/Staff được gán branch, một Customer; integration test kiểm branch scope thay đổi ngay và test chặn Manager/Staff không assignment active.\n`;
research = research.replace('\n### Decision: Không xây engine giảm giá hợp nhất', decision + '\n### Decision: Không xây engine giảm giá hợp nhất');
research = research.replace('Số dư điểm âm (Req 17.4) — Chính sách HQ cấu hình:', 'Số dư điểm âm (Req 17.4) — Chính sách Admin cấu hình:');
fs.writeFileSync(researchPath, research);

console.log('Updated design and research with UserAccount/RBAC/assignment model.');
