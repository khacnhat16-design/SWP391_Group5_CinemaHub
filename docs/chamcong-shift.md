# Nghiệp vụ Chấm công & Đối soát Ca làm việc (Shift / Attendance)

> Tài liệu mô tả nghiệp vụ **chấm công nhân viên rạp chiếu phim** dựa trên mô hình
> **mở ca – bán hàng trong ca – đối soát tiền mặt cuối ca – phê duyệt**.
>
> Phạm vi: `ShiftService`, `ShiftDAO`, `ShiftController`, `AppContextListener.ExpiryScheduler`,
> dashboard Admin / Branch Manager.

---

## 1. Mục tiêu nghiệp vụ

| Mục tiêu | Mô tả |
|-----------|-------|
| **Chấm công** | Ghi nhận giờ vào/ra của nhân viên theo từng ca làm việc thực tế tại quầy. |
| **Đối soát tiền** | So sánh tiền mặt thực tế trong két với tiền hệ thống ghi nhận → phát hiện thừa/thiếu. |
| **Giải trình** | Nhân viên ghi chú lý do khi chênh lệch vượt ngưỡng; Manager xét duyệt. |
| **Audit** | Toàn bộ thao tác mở/đóng/duyệt ca đều được audit log (Req 15.3). |

---

## 2. Tác nhân & Phạm vi

### 2.1 Vai trò

| Vai trò | Quyền với ca |
|---------|--------------|
| `BRANCH_STAFF` | Tự mở/đóng ca của chính mình. Không thấy ca người khác. |
| `BRANCH_MANAGER` | Xem/duyệt tất cả ca thuộc chi nhánh mình quản lý. |
| `ADMIN` | Xem toàn chuỗi, xem báo cáo đối soát theo từng chi nhánh. |

### 2.2 Trạng thái ca (`shift.status`)

```
   ┌────────┐ open    ┌─────────┐ close (trong ngưỡng)  ┌──────────┐
   │ (none) │────────▶│  OPEN   │───────────────────────▶│ APPROVED │
   └────────┘         └─────────┘                        └──────────┘
                            │
                            │ close (vượt ngưỡng + có note)
                            ▼
                     ┌────────────────────┐ approve  ┌──────────┐
                     │ PENDING_APPROVAL   │─────────▶│ APPROVED │
                     │ (Manager xét)      │          └──────────┘
                     │                    │ reject   ┌──────────┐
                     │                    │─────────▶│ REJECTED │
                     └────────────────────┘          └──────────┘
```

| Status | Mô tả | Ai đặt |
|--------|-------|-------|
| `OPEN` | Ca đang mở, nhân viên đang bán hàng. | Staff tự mở |
| `PENDING_APPROVAL` | Đã đóng, chênh lệch vượt ngưỡng → chờ Manager. | Hệ thống (đóng ca) |
| `APPROVED` | Đã được Manager chấp nhận. | Manager |
| `REJECTED` | Manager trả lại, yêu cầu giải trình thêm. | Manager |

---

## 3. Quy trình nghiệp vụ chi tiết

### 3.1 Mở ca (Open Shift) — Req 22.1, 22.6

**Trigger:** Nhân viên đến ca, đến quầy POS, khai báo tiền mặt đầu ca.

**Bước thực hiện (FE → BE):**

```
POST /shift/open
Body: { openingCash: 500000 }

→ 200 OK { id, status: "OPEN", openingCash, openedAt, ... }
```

**Business rules:**

| # | Quy tắc | Mã lỗi |
|---|---------|---------|
| 1 | `openingCash >= 0` (không âm) | `VALIDATION` |
| 2 | Nhân viên **không được có ca OPEN khác** đang mở | `CONFLICT` |
| 3 | Ca gắn với `branchId` của `staff.branch_assignment` hiện tại | — |
| 4 | Audit log `OPEN_SHIFT` ghi lại `openingCash` | Req 15.3 |

**Mã SQL chốt ở DB (`UX_shift_open_per_staff`):** đảm bảo 1 staff chỉ có 1 ca OPEN.

### 3.2 Giao dịch trong ca

Mọi giao dịch tiền mặt (`PaymentService`, `ConcessionService`) trong ca sẽ được
`ShiftDAO.attachPaymentToOpenShift` gắn vào `shift_id` hiện tại (Req 22.2).

Hệ thống tự động:

- **Cộng** tổng tiền theo `method` (`CASH`, `VNPAY`, `WALLET`, …) vào ca.
- **Trừ** tổng tiền hoàn (`refund_total`) khi Staff hủy vé / hoàn tiền trong ca.

> ⚠️ Lưu ý: Hiện tại `refundTotal` được tính gộp cho **mọi phương thức** (theo
> `payment.status='REFUNDED'`). Khi có cột `refund_method` sẽ refine lại để trừ
> chính xác tiền mặt hoàn.

### 3.3 Đóng ca (Close Shift) — Req 22.3, 22.4

**Trigger:** Cuối ca, nhân viên đếm tiền thực tế trong két và nhập vào hệ thống.

```
POST /shift/close
Body: { shiftId, closingCashActual, discrepancyNote? }
```

**Luồng xử lý (trong `ShiftService.closeShift`):**

```
1. Kiểm tra ca tồn tại, status = OPEN, đúng chủ sở hữu.
2. Tổng hợp Shift.Summary từ DB (ShiftDAO.summarize).
3. Tính expected_cash = openingCash + cashTotal - refundTotal
4. Tính discrepancy = closingCashActual - expectedCash
5. Quyết định status mới:
   - |discrepancy| <= tolerance (100.000đ mặc định) → APPROVED
   - |discrepancy| >  tolerance  VÀ CÓ note        → PENDING_APPROVAL
   - |discrepancy| >  tolerance  VÀ KHÔNG CÓ note  → 400 (bắt buộc note)
6. UPDATE shift (closeGuarded) với status mới, reason.
7. Audit log CLOSE_SHIFT.
```

**Bảng ngưỡng dung sai:**

| `tolerance` | Hành vi khi sai lệch |
|-------------|----------------------|
| `100.000đ` (mặc định) | Sai lệch ≤ 100k → APPROVED ngay. |
| Tăng/giảm (qua constructor `ShiftService(tolerance)`) | Linh hoạt theo policy từng rạp. |

### 3.4 Phê duyệt / Từ chối ca — Req 22.5

**Manager/Admin xử lý ca `PENDING_APPROVAL`:**

| Hành động | API | Khi nào |
|-----------|-----|---------|
| Phê duyệt | `POST /shift/approve` `{ shiftId, note? }` | Đồng ý với giải trình |
| Trả lại | `POST /shift/reject` `{ shiftId, note }` | Yêu cầu giải trình thêm (`note` bắt buộc) |

**Ràng buộc:**
- Chỉ ca ở `PENDING_APPROVAL` mới có thể `approve`/`reject` (lưu ý chống race).
- Dùng `updateApprovalGuarded` với status cũ = `PENDING_APPROVAL` để chống 2 manager cùng duyệt.
- Audit `APPROVE_SHIFT` / `REJECT_SHIFT`.

### 3.5 Xem lịch sử & đối soát — Req 22.5, 22.7

| API | Mô tả | Phân quyền |
|-----|-------|------------|
| `GET /shift/current` | Ca OPEN của Staff đang đăng nhập. | Staff |
| `GET /shift/history?from=&to=&branchId=` | Lịch sử ca. | Manager: chi nhánh mình · Admin: toàn chuỗi |
| `GET /shift/pending` | Ca đang chờ duyệt. | Manager/Admin |
| `GET /shift/{id}` | Chi tiết ca + summary. | Manager/Admin (cùng chi nhánh) |
| `GET /shift/reconcile?from=&to=` | Tổng hợp đối soát toàn chuỗi. | Admin |

---

## 4. Cấu trúc bảng `shift`

```sql
CREATE TABLE dbo.shift (
    id                   BIGINT IDENTITY PRIMARY KEY,
    branch_id            BIGINT NOT NULL FOREIGN KEY REFERENCES dbo.branch(id),
    staff_id             BIGINT NOT NULL FOREIGN KEY REFERENCES dbo.[user](id),
    status               VARCHAR(32) NOT NULL,         -- OPEN / PENDING_APPROVAL / APPROVED / REJECTED
    opening_cash         BIGINT NOT NULL DEFAULT 0,
    expected_cash        BIGINT NULL,                    -- tính lúc đóng ca
    closing_cash_actual  BIGINT NULL,                    -- tiền thực kiểm
    discrepancy_note     NVARCHAR(MAX) NULL,             -- giải trình
    approved_by          BIGINT NULL FOREIGN KEY REFERENCES dbo.[user](id),
    approved_at          DATETIME2 NULL,
    opened_at            DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    closed_at            DATETIME2 NULL,
    version              INT NOT NULL DEFAULT 0,
    CONSTRAINT UX_shift_open_per_staff UNIQUE (staff_id, status)  -- 1 staff, 1 OPEN
);
```

> Index phụ `(branch_id, opened_at)` phục vụ truy vấn lịch sử + reconcile.

---

## 5. Các trường hợp đặc biệt (Edge cases)

| Tình huống | Xử lý |
|------------|-------|
| Staff quên đóng ca → hết phiên | `ExpiryScheduler` (chạy 30s/lần) tự `cancelExpiredPendingTickets`; ca OPEN quá 24h có thể ép close. |
| 2 Manager duyệt cùng lúc | `updateApprovalGuarded` chỉ update khi status cũ = `PENDING_APPROVAL`, ai chậm → `CONFLICT`. |
| Đóng ca nhầm ca người khác | Service check `shift.staffId == actorStaffId` → `FORBIDDEN`. |
| Sai lệch = 0 | APPROVED ngay, không cần note. |
| Sai lệch trong ngưỡng nhưng có note | APPROVED, lưu note làm lịch sử. |
| Staff chưa được gán chi nhánh | Service ném `Forbidden("Chưa được gán chi nhánh")`. |

---

## 6. Dashboard cho Admin / Manager

Phần **"Chấm công & đối soát ca gần đây"** trên Dashboard (`renderDashboardShiftSection`):

- **4 thẻ đếm trạng thái**: `OPEN` / `PENDING_APPROVAL` / `APPROVED` / `REJECTED`.
- **Bảng top 5 ca sai lệch lớn nhất**: hiển thị nhân viên, chi nhánh, tiền đầu ca,
  tiền thực kiểm, **discrepancy tô màu** (đỏ nếu vượt 100k, xanh nếu trong ngưỡng).
- Liên kết "Xem tất cả ca có sai lệch →" dẫn về module `shift`.

Gọi API: `GET /shift/history` (Manager) hoặc `GET /shift/reconcile` (Admin).

---

## 7. Tích hợp với POS

Khi nhân viên thao tác POS (bán vé, F&B):

1. `PaymentService.createPayment` đọc `shiftDao.findOpenByStaff(staffId)`.
2. Nếu ca mở → set `payment.shift_id = shift.id`.
3. Tổng kết ca cuối ngày dùng `payment.shift_id` để gom giao dịch.

> Đảm bảo **mọi giao dịch đều thuộc về một ca** — không có payment "treo".

---

## 8. Test case tiêu biểu

| # | Kịch bản | Input | Expected |
|---|----------|-------|----------|
| 1 | Mở ca hợp lệ | `openingCash=500000` | 200, status=OPEN |
| 2 | Mở ca khi đã có ca mở | — | 409 `Bạn đang có ca mở` |
| 3 | Đóng ca trong ngưỡng | `actual=600000`, expected=595000 | 200, status=APPROVED |
| 4 | Đóng ca vượt ngưỡng, không note | `actual=300000`, expected=595000 | 400 `DISCREPANCY_REASON_REQUIRED` |
| 5 | Đóng ca vượt ngưỡng, có note | `actual=300000`, expected=595000, note="..." | 200, status=PENDING_APPROVAL |
| 6 | Manager duyệt ca | — | 200, status=APPROVED, audit log |
| 7 | Manager reject không note | — | 400 `Trả lại ca bắt buộc kèm ghi chú` |
| 8 | Staff khác đóng ca người ta | — | 403 `Chỉ người mở ca mới được đóng ca` |

---

## 9. API Reference tóm tắt

| Endpoint | Method | Quyền | Mục đích |
|----------|--------|-------|---------|
| `/shift/open` | POST | Staff | Mở ca |
| `/shift/close` | POST | Staff | Đóng ca + đối soát |
| `/shift/approve` | POST | Manager | Duyệt ca |
| `/shift/reject` | POST | Manager | Trả lại ca |
| `/shift/current` | GET | Staff | Ca đang mở |
| `/shift/history` | GET | Manager/Admin | Lịch sử ca |
| `/shift/pending` | GET | Manager/Admin | Ca chờ duyệt |
| `/shift/{id}` | GET | Manager/Admin | Chi tiết ca |
| `/shift/reconcile` | GET | Admin | Đối soát toàn chuỗi |

---

## 10. Lưu ý vận hành

- **Không xóa ca đã đóng** — chỉ phục vụ audit/kiểm toán.
- **Không tự ý chỉnh tolerance** mà không thông báo Manager — ảnh hưởng policy duyệt.
- **Đóng ca trước khi tan ca** — tránh ca OPEN bị treo qua đêm.
- **Có note tiếng Việt có dấu** khi giải trình — phục vụ audit sau này.
- **Tách bạch thao tác Manager/Admin** — không dùng tài khoản Staff để duyệt ca.
