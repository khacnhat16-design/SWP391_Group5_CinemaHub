# CRUD Audit Report — CinemaHub Management

**Project:** `cinema-management` (Java Servlet + JSP + vanilla JS)
**Audit Date:** 2026-09-17
**Auditor:** Claude Code
**Style:** `workspace.css` — white sidebar + blue accent, rounded cards, compact tables

---

## 1. CRUD Pages Inventory

| Page | Module Key | List | Create | Edit | Delete | Search | Filter | Pagination | Status Badge | Empty State | Loading | Toggle Status | Roles |
|------|-----------|:----:|:------:|:----:|:------:|:------:|:------:|:-----------:|:------------:|:------------:|:-------:|:-------------:|-------|
| **User Management** | `users` | ✅ table | ❌ | ❌ | ❌ | ✅ text | ✅ role dropdown | ❌ | ❌ plain text | ✅ | ❌ | ❌ | ADMIN |
| **Branch Management** | `branch` | ✅ table | ✅ modal | ✅ modal | ❌ | ❌ | ❌ | ❌ | ✅ ws-badge | ✅ | ✅ | ✅ deactivate | ADMIN |
| **Movie Management** | `movie` | ✅ table | ✅ modal | ✅ modal | ❌ | ❌ | ❌ | ❌ | ✅ ws-badge | ✅ | ✅ | ✅ archive | ADMIN |
| **Screen Management** | `screen` | ✅ table | ✅ modal | ✅ modal | ❌ | ❌ | ❌ branch selector | ❌ | ✅ ws-badge | ✅ | ✅ | ✅ deactivate | ADMIN, BRANCH_MANAGER |
| **Showtime Management** | `showtime` | ✅ table | ❌ | ❌ | ❌ | ❌ | ✅ branch+movie+date+status | ❌ limit=100 | ✅ ws-badge | ✅ | ✅ | ❌ | ADMIN, BRANCH_MANAGER |
| **Shift History** | `shift` | ✅ table | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ ws-badge | ✅ | ✅ | ❌ | BRANCH_STAFF, BRANCH_MANAGER |
| **Booking / My Tickets** | `booking` | ✅ table | ❌ | ❌ | ✅ cancel | ❌ | ❌ | ❌ | ✅ ws-badge | ✅ | ✅ | ❌ | ALL (customer) |
| **Concession Orders (Mine)** | `concession` | ✅ table | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ ws-badge | ✅ | ✅ | ❌ | CUSTOMER |
| **Product Management** | `product` | ✅ table | ✅ modal | ✅ modal (price only) | ❌ | ❌ | ❌ | ❌ | ✅ ws-badge | ✅ | ✅ | ✅ deactivate | ADMIN |
| **Pricing Rules** | `pricing` | ✅ table | ✅ modal | ✅ modal | ✅ DELETE | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ADMIN |
| **Inventory** | `inventory` | ✅ table | ✅ modal (stock in) | ✅ modal (adjust) | ❌ | ❌ | ✅ branch selector | ❌ | ❌ | ✅ | ✅ | ❌ | ADMIN, BRANCH_MANAGER |
| **Notifications** | `notification` | ✅ table | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ ws-badge | ✅ | ✅ | ❌ | ALL |
| **Report** | `report` | ✅ kpi+table | ❌ | ❌ | ❌ | ❌ | ✅ date range | ❌ | ❌ | ✅ | ✅ | ❌ | ADMIN, BRANCH_MANAGER |
| **F&B Catalog** | `fnb-catalog` | ✅ card grid | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | CUSTOMER |
| **F&B Cart** | `fnb-cart` | ✅ cart items | ❌ | ❌ | ✅ remove item | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | CUSTOMER |
| **F&B Orders (Mine)** | `fnb-orders` | ✅ table | ❌ | ❌ | ✅ cancel (PENDING) | ❌ | ✅ status filter | ❌ | ✅ ws-badge | ✅ | ✅ | ❌ | CUSTOMER |
| **Staff F&B Orders** | `concession-orders` | ✅ table | ❌ | ❌ | ❌ | ✅ text search | ✅ status+date | ✅ prev/next | ✅ ws-badge | ✅ | ✅ | ❌ | BRANCH_STAFF, BRANCH_MANAGER, ADMIN |
| **Staff F&B Pickup** | `concession-pickup` | ❌ (scanner) | ❌ | ❌ | ❌ | ✅ manual input | ❌ | ❌ | ✅ banner | ✅ | ✅ | ❌ | BRANCH_STAFF, BRANCH_MANAGER, ADMIN |
| **Customer Points** | `customer-points` | ✅ ledger table | ❌ | ✅ adjust modal | ❌ | ✅ customer search | ❌ | ❌ | ✅ color-coded delta | ✅ | ✅ | ❌ | BRANCH_STAFF, BRANCH_MANAGER, ADMIN |
| **POS Ticket** | `pos-ticket` | ❌ (external pos-staff.js) | — | — | — | — | — | — | — | — | — | — | BRANCH_STAFF |
| **POS Concession** | `pos-concession` | ❌ (external pos-staff.js) | — | — | — | — | — | — | — | — | — | — | BRANCH_STAFF |
| **Dashboard** | `dashboard` | ✅ kpi grid | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ALL |

---

## 2. UI Implementation Layers

### A. Rendering Strategy

| Strategy | Pages | Notes |
|----------|-------|-------|
| **rest-page.js** (`CinemaHubPages`) | branch, movie, screen, shift, booking, product, pricing, inventory, report, notification, fnb-\*, concession | Reusable renderTable + modal helpers. Used by both standalone JSP pages (via `<meta name="rest-page">`) and `/console` (via `console.js` MODULES). |
| **console.js** (inline modules) | dashboard, profile, users, wallet, discovery, seat, validate, concession-orders, concession-pickup, customer-points | Custom JS render. Also delegates to `CinemaHubPages` for business modules. |
| **pos-staff.js** (external) | pos-ticket, pos-concession | Full POS UI. Not audited in this report. |

### B. Servlet URLs Used by CRUD Pages

| Page | API Endpoint | HTTP | Used In |
|------|-------------|------|---------|
| Users | `/api/users` | GET | moduleUsers |
| Users | `/api/users/{id}` | GET/PUT | moduleCustomerPoints |
| Branches | `/branch` | GET/POST | pageBranch |
| Branches | `/branch/{id}` | PUT | pageBranch |
| Movies | `/movie` | GET/POST | pageMovie |
| Movies | `/movie/{id}` | PUT | pageMovie |
| Screens | `/screen` | GET/POST | pageScreen |
| Screens | `/screen/{id}` | PUT | pageScreen |
| Showtimes | `/showtime/manage/search` | GET | pageShowtime |
| Showtimes | `/showtime/{id}` | GET | moduleSeat |
| Shifts | `/shift/history` | GET | pageShift |
| Bookings | `/booking/mine` | GET | pageBooking |
| Bookings | `/booking/cancel` | POST | pageBooking |
| Bookings | `/booking/validate` | POST | moduleValidate |
| Products | `/concession/products` | GET/POST | pageProduct |
| Products | `/concession/products/{id}` | PUT | pageProduct |
| Inventory | `/inventory` | GET/POST | pageInventory |
| Pricing | `/pricing/rules` | GET/POST | pagePricing |
| Pricing | `/pricing/rules/{id}` | PUT/DELETE | pagePricing |
| Notifications | `/notification` | GET | pageNotification |
| Notifications | `/notification/read-all` | POST | pageNotification |
| Reports | `/report/summary` | GET | pageReport |
| Reports | `/api/dashboard` | GET | moduleDashboard |
| F&B Orders | `/concession/orders` | GET | moduleConcessionOrders |
| F&B Orders | `/concession/orders/{id}` | GET | moduleConcessionOrders |
| F&B Orders | `/concession/redeem` | POST | moduleConcessionOrders |
| F&B Orders | `/concession/scan` | POST | moduleConcessionPickup |
| F&B Orders | `/concession/orders/mine` | GET | pageConcession, pageFnbOrders |
| Loyalty | `/loyalty/adjust` | POST | moduleCustomerPoints |
| Loyalty | `/loyalty/ledger` | GET | moduleCustomerPoints |
| Loyalty | `/loyalty/info` | GET | pageFnbCart |
| Upload | `/upload` | POST (via hub.uploadFile) | pageMovie poster |

---

## 3. CSS Inventory (`workspace.css`)

### Layout
| Class | Purpose |
|-------|---------|
| `.ws-sidebar` | Fixed left sidebar, 256px, white bg |
| `.ws-sidebar-brand` | Logo area (mark + text) |
| `.ws-sidebar-menu` | Scrollable nav container |
| `.ws-menu-group` | Grouped nav items with label |
| `.ws-menu-group-label` | Uppercase label above nav group |
| `.ws-menu-link` | Nav item with icon + label |
| `.ws-menu-link.active` | Active nav highlight |
| `.ws-sidebar-footer` | User card + branch info |
| `.ws-main` | Main area (margin-left: 256px) |
| `.ws-main-inner` | Inner padding wrapper |
| `.ws-backdrop` | Mobile overlay behind sidebar |

### Header
| Class | Purpose |
|-------|---------|
| `.ws-header` | Sticky top bar, 64px |
| `.ws-header-title` | Eyebrow + h1 |
| `.ws-header-search` | Search input with icon |
| `.ws-icon-btn` | Icon-only button (bell, etc.) |
| `.ws-bell-badge` | Notification count badge |
| `.ws-user-chip` | Avatar + name + caret |
| `.ws-dropdown` | User/notification dropdown |

### KPI & Cards
| Class | Purpose |
|-------|---------|
| `.ws-kpi-grid` | 4-column responsive grid |
| `.ws-kpi` | Single KPI card |
| `.ws-kpi-head` | KPI label + icon row |
| `.ws-kpi-icon.blue/green/orange/purple` | KPI icon color variants |
| `.ws-kpi-value` | Large bold value |
| `.ws-kpi-delta` | Change indicator |
| `.ws-card` | Generic card container |
| `.ws-card-head` | Card title + tabs/actions |
| `.ws-card-title` | Card heading |
| `.ws-card-tabs` | Tab switcher (pill style) |
| `.ws-card-link` | View-all link |

### Tables
| Class | Purpose |
|-------|---------|
| `.ws-table` | Full-width table, no outer border |
| `.ws-table th` | Uppercase header, light bg |
| `.ws-table td` | Cell with bottom border |
| `.ws-table tr:hover td` | Row hover |
| `.ws-table-wrap` | `overflow-x: auto` wrapper |

### Buttons
| Class | Purpose |
|-------|---------|
| `.ws-btn` | Base button |
| `.ws-btn.primary` | Blue filled |
| `.ws-btn.secondary` | White/outlined |
| `.ws-btn.ghost` | Transparent |
| `.ws-btn.danger` | Red soft bg |
| `.ws-btn.small` | Compact size |
| `.ws-btn-sm` | Alias for small |
| `.ws-btn-lg` | Large size |

### Forms
| Class | Purpose |
|-------|---------|
| `.ws-form` | Grid gap container |
| `.ws-form label` | Label above input |
| `.ws-form input/select/textarea` | Styled form controls |
| `.ws-form-actions` | Right-aligned action row |
| `.ws-form-grid` | 2-column form layout |
| `.ws-form-grid .full` | Full-width field |
| `.ws-field` | Label + control wrapper |
| `.ws-input` | Generic text input |
| `.ws-input-sm` | Small input |
| `.ws-select` | Styled select |
| `.ws-radio-label` | Radio option row |
| `.ws-textarea` | Multi-line input |
| `.ws-form-label` | Uppercase label above form section |

### Modals
| Class | Purpose |
|-------|---------|
| `.ws-modal-overlay` | Fixed overlay backdrop |
| `.ws-modal` | Modal panel (max 520px) |
| `.ws-modal-backdrop` | Semi-transparent backdrop |
| `.ws-modal-panel` | Content panel |
| `.ws-modal-head` | Header with title + close |
| `.ws-modal-x` | Close button |
| `.ws-modal-body` | Scrollable body |

### Toolbar & Search
| Class | Purpose |
|-------|---------|
| `.ws-toolbar` | Flex toolbar with wrap |
| `.ws-toolbar .search` | Search with icon |
| `.ws-toolbar-row` | Inline toolbar row |

### Status & Badges
| Class | Purpose |
|-------|---------|
| `.ws-badge` | Inline pill badge |
| `.ws-badge.green/blue/red/orange/gray/purple` | Color variants |
| `.ws-badge.ok/warn/err/neutral` | Semantic variants |
| `.ws-status-pill` | Status with dot prefix |
| `.ws-badge-green/orange/red/blue/purple/gray` | Shorthand badge colors |

### Empty & Loading
| Class | Purpose |
|-------|---------|
| `.ws-empty` | Centered empty state |
| `.ws-loading` | Centered loading state |
| `.ws-spinner` | CSS spinner animation |

### Progress & Breakdown
| Class | Purpose |
|-------|---------|
| `.ws-progress` | Progress bar track |
| `.ws-progress-bar` | Progress bar fill |
| `.ws-progress-bar.green/orange/red` | Color variants |
| `.ws-breakdown-row` | Label + value row |

### POS & Seat
| Class | Purpose |
|-------|---------|
| `.pos-layout` | POS 2-column grid |
| `.pos-seat-area` | Seat map container |
| `.pos-seat-grid` | Seat grid container |
| `.pos-seat-row` | Seat row with label |
| `.pos-seat` | Individual seat button |
| `.pos-seat.available/hold/sold/selected/vip/couple` | Seat state colors |
| `.pos-product-grid` | Product card grid |
| `.pos-product` | Product card |
| `.pos-cart` | Cart section |
| `.pos-cart-total` | Cart total row |
| `.pos-order-summary` | Order summary |

### F&B Customer UI
| Class | Purpose |
|-------|---------|
| `.fnb-catalog-grid` | Catalog sections |
| `.fnb-type-section` | Product type section |
| `.fnb-type-title` | Section heading |
| `.fnb-product-grid` | Product cards grid |
| `.fnb-product-card` | Product card |
| `.fnb-product-img` | Product image |
| `.fnb-product-info` | Product details |
| `.fnb-cart-page` | Cart page layout |
| `.fnb-cart-layout` | 2-column cart layout |
| `.fnb-cart-items` | Cart item list |
| `.fnb-cart-item` | Cart item row |
| `.fnb-cart-summary` | Sticky summary card |
| `.fnb-qty-ctrl` | Quantity control buttons |
| `.fnb-qty-val` | Quantity display |
| `.fnb-qty-input` | Quantity input |
| `.fnb-pickup-row` | Pickup code + barcode |
| `.fnb-order-lines` | Order items section |
| `.fnb-order-line` | Single order item |
| `.fnb-order-timeline` | Order status timeline |
| `.fnb-timeline-step` | Timeline step |
| `.fnb-timeline-dot` | Timeline dot |
| `.fnb-order-total` | Order total |
| `.fnb-points-row` | Points row |
| `.fnb-discount-row` | Discount display |
| `.fnb-summary-section` | Summary section |
| `.fnb-subtotal-row` | Subtotal row |
| `.fnb-final-row` | Final total row |
| `.fnb-orders-page` | Orders page |
| `.fnb-order-detail-header` | Order header |

### Layout Patterns
- **CSS Grid**: `.ws-kpi-grid` (4-col), `.ws-row.cols-2` (2fr+1fr), `.ws-row.cols-1-2` (1fr+2fr), `.ws-form-grid` (2-col), `.ws-breakdown-row` (auto-fit), `.pos-layout` (1fr+380px), `.pos-product-grid` (auto-fill minmax 150px), `.fnb-product-grid` (auto-fill minmax 200px), `.fnb-cart-layout` (1fr+360px)
- **Flexbox**: Sidebar nav, toolbar, card head, header, row-actions, form-actions
- **Responsive breakpoints**: `@media (max-width: 1100px)`, `@media (max-width: 960px)`, `@media (max-width: 760px)`, `@media (max-width: 700px)`, `@media (max-width: 860px)`, `@media (max-width: 600px)`

### Color Tokens
```css
--ws-bg: #f5f7fb          /* page background */
--ws-card: #ffffff         /* card bg */
--ws-line: #e5e7eb         /* borders */
--ws-text: #1f2937         /* text */
--ws-text-muted: #6b7280   /* muted text */
--ws-accent: #2563eb       /* primary blue */
--ws-success: #16a34a      /* green */
--ws-warn: #d97706         /* orange */
--ws-danger: #dc2626        /* red */
--ws-info: #0891b2         /* cyan */
```

---

## 4. Sidebar Menu Map

### ADMIN

| Menu | URL | Module | Sub-group |
|------|-----|--------|-----------|
| Bảng điều khiển | `/console?module=dashboard` | dashboard | — |
| Người dùng | `/console?module=users` | users | Quản lý hệ thống |
| Chi nhánh | `/console?module=branch` | branch | Quản lý hệ thống |
| Phim | `/console?module=movie` | movie | Quản lý hệ thống |
| Phòng chiếu | `/console?module=screen` | screen | Quản lý hệ thống |
| Suất chiếu | `/console?module=showtime` | showtime | Quản lý hệ thống |
| Đặt vé | `/console?module=booking` | booking | Vận hành |
| Vé | `/console?module=ticket` | ticket | Vận hành |
| Đồ ăn và thức uống | `/console?module=product` | product | Vận hành |
| Kho hàng | `/console?module=inventory` | inventory | Vận hành |
| Bảng giá | `/console?module=pricing` | pricing | Kinh doanh |
| Giao dịch ví | `/console?module=wallet` | wallet | Kinh doanh |
| Báo cáo | `/console?module=report` | report | Kinh doanh |
| Thông báo | `/console?module=notification` | notification | Hệ thống |
| Hồ sơ | `/console?module=profile` | profile | Hệ thống |
| Đăng xuất | `/logout` | — | Hệ thống |

### BRANCH_MANAGER

| Menu | URL | Module | Sub-group |
|------|-----|--------|-----------|
| Bảng điều khiển | `/console?module=dashboard` | dashboard | — |
| Tổng quan chi nhánh | `/console?module=branch` | branch | Quản lý chi nhánh |
| Phim | `/console?module=movie` | movie | Quản lý chi nhánh |
| Phòng và ghế | `/console?module=screen` | screen | Quản lý chi nhánh |
| Suất chiếu | `/console?module=showtime` | showtime | Quản lý chi nhánh |
| Đặt vé | `/console?module=booking` | booking | Vận hành |
| Vé | `/console?module=ticket` | ticket | Vận hành |
| Đồ ăn và thức uống | `/console?module=product` | product | Vận hành |
| Kho hàng | `/console?module=inventory` | inventory | Vận hành |
| Ca làm việc của nhân viên | `/console?module=shift` | shift | Vận hành |
| Đơn F&B | `/console?module=concession-orders` | concession-orders | Quản lý F&B |
| Quét mã F&B | `/console?module=concession-pickup` | concession-pickup | Quản lý F&B |
| Điều chỉnh điểm | `/console?module=customer-points` | customer-points | Quản lý F&B |
| Báo cáo | `/console?module=report` | report | Kinh doanh |
| Thông báo | `/console?module=notification` | notification | Hệ thống |
| Hồ sơ | `/console?module=profile` | profile | Hệ thống |
| Đăng xuất | `/logout` | — | Hệ thống |

### BRANCH_STAFF

| Menu | URL | Module | Sub-group |
|------|-----|--------|-----------|
| Bán vé | `/console?module=pos-ticket` | pos-ticket | Bán hàng |
| Đồ ăn và thức uống | `/console?module=pos-concession` | pos-concession | Bán hàng |
| Nhận đơn trực tuyến | `/console?module=pickup` | pickup | Bán hàng |
| Đơn F&B | `/console?module=concession-orders` | concession-orders | Quản lý F&B |
| Quét mã F&B | `/console?module=concession-pickup` | concession-pickup | Quản lý F&B |
| Điều chỉnh điểm | `/console?module=customer-points` | customer-points | Khách hàng |
| Kiểm tra vé | `/console?module=validate` | validate | Vé |
| Lịch sử vé | `/console?module=ticket` | ticket | Vé |
| Ca hiện tại | `/console?module=shift` | shift | Ca làm việc |
| Thông báo | `/console?module=notification` | notification | Hệ thống |
| Hồ sơ | `/console?module=profile` | profile | Hệ thống |
| Đăng xuất | `/logout` | — | Hệ thống |

### CUSTOMER

| Menu | URL | Module | Sub-group |
|------|-----|--------|-----------|
| Thực đơn F&B | `/console?module=fnb-catalog` | fnb-catalog | Bắp & Nước |
| Đơn F&B của tôi | `/console?module=fnb-orders` | fnb-orders | Bắp & Nước |
| Hồ sơ | `/console?module=profile` | profile | Tài khoản |
| Đăng xuất | `/logout` | — | Tài khoản |

---

## 5. Priority Upgrade List

### 🔴 CRITICAL — Core Admin Features

1. **User Management** (`users`)
   - **Issue:** Read-only table. No Create, Edit, Delete. No status badge. No pagination.
   - **Impact:** Admin cannot manage user accounts.
   - **Action:** Add create modal (name, email, phone, role, branch assignment), edit modal, delete/reset modal, status toggle, pagination.

2. **Branch Management** (`branch`)
   - **Issue:** Edit modal is too simple (only name/address/phone). Missing: operating hours, status description, manager assignment.
   - **Impact:** Incomplete branch configuration.
   - **Action:** Expand edit modal, add delete confirmation, add status description field.

3. **Showtime Management** (`showtime`)
   - **Issue:** List only. No Create, no Edit, no Cancel. Limit=100 hardcoded.
   - **Impact:** Cannot manage showtimes from UI.
   - **Action:** Add create showtime wizard (movie → branch → screen → date/time → price rules), edit, cancel.

### 🟠 HIGH — Operational Core

4. **Screen Management** (`screen`)
   - **Issue:** Edit modal missing seat type config (VIP rows not saved). No seat layout editor.
   - **Impact:** Screen seat configuration incomplete.
   - **Action:** Add seat layout visual editor, configure seat types (VIP, COUPLE, STANDARD) per row.

5. **Shift Management** (`shift`)
   - **Issue:** Read-only history. No open/close shift UI. No cash reconciliation.
   - **Impact:** Staff cannot open/close shifts from UI.
   - **Action:** Add open-shift button, close-shift with cash counting, discrepancy handling.

6. **Movie Management** (`movie`)
   - **Issue:** Missing: trailer URL, cast/crew, genres array, multiple ratings, bulk status toggle.
   - **Impact:** Incomplete movie catalog.
   - **Action:** Expand form with all movie metadata fields.

7. **Pricing Rules** (`pricing`)
   - **Issue:** Only price + basic fields. No day-type/time-slot visual editor. No bulk generation.
   - **Impact:** Complex pricing not manageable.
   - **Action:** Add matrix editor (day-type × time-slot × seat-type), bulk generate weekdays/weekends.

### 🟡 MEDIUM — F&B & Revenue

8. **Inventory** (`inventory`)
   - **Issue:** Adjust modal only does ±diff. No low-stock alerts. No reorder suggestion.
   - **Impact:** Staff cannot track stock levels proactively.
   - **Action:** Add stock level indicators, low-stock warning threshold, reorder history.

9. **Staff F&B Orders** (`concession-orders`)
   - **Issue:** No update status action (READY_FOR_PICKUP → FULFILLED is only redeem via pickup scanner).
   - **Impact:** Orders stuck in PAID cannot be manually marked ready.
   - **Action:** Add status update buttons (Mark Ready, Mark Fulfilled) directly in table.

10. **Product Management** (`product`)
    - **Issue:** Edit only price/name/unit/type. No image upload. No combo component editor.
    - **Impact:** Cannot manage full product catalog visually.
    - **Action:** Add image upload, combo builder with component selection and auto-price.

### 🟢 LOW — Polish & Insights

11. **Booking** (`booking`)
    - **Issue:** No filter by date, movie, status. No export. No barcode display.
    - **Impact:** Hard to find specific tickets.
    - **Action:** Add date range filter, status filter, search by ticket code.

12. **Report** (`report`)
    - **Issue:** Very basic. No export to PDF/Excel. No branch comparison. No trend charts.
    - **Impact:** Limited analytical value.
    - **Action:** Add SVG bar charts, date range presets, export, branch breakdown.

13. **Notification** (`notification`)
    - **Issue:** No pagination. No unread filter. No delete.
    - **Impact:** Cluttered over time.
    - **Action:** Add pagination, unread-only filter, delete old notifications.

14. **Customer Points** (`customer-points`)
    - **Issue:** Search by exact match only. No customer list view. No tier management.
    - **Impact:** Difficult to find customers for point adjustment.
    - **Action:** Add paginated customer list, tier upgrade/downgrade, point transaction history export.

---

## 6. Cross-Cutting Observations

### Architecture Pattern
- **All CRUD is client-side JS** (rest-page.js + console.js). JSP files are thin shells (`<c:set>` + include).
- Two rendering paths: (1) standalone JSP with `<meta name="rest-page">` triggers `rest-page.js` directly; (2) `/console` route via `console.js MODULES` which delegates to `CinemaHubPages`.
- No server-side rendering for CRUD data.

### API Consistency
- Most endpoints follow REST conventions: `GET /{resource}`, `POST /{resource}`, `PUT /{resource}/{id}`, `DELETE /{resource}/{id}`.
- Some use action-based: `PUT /{resource}/{id} {action: 'deactivate'}`, `POST /booking/cancel`.
- Response envelope inconsistency: some return arrays directly, others return `{items: [], total: N}`.

### Missing Patterns
- No optimistic locking (no version fields in edit forms).
- No undo/soft-delete for most entities.
- No bulk operations (multi-select + batch edit/delete).
- No inline editing — all edits go through modals.
- No toast notification for create/edit success/failure in standalone JSP pages (only via `hub.notify` in console.js).
- No keyboard shortcuts (e.g., `N` for new, `Esc` to close modal).

### Accessibility
- Modals use `role="dialog"` + `aria-modal` but no focus trap.
- Tables lack `aria-label` on headers.
- No skip-to-content link.
- Loading states use `role="status"` but empty states lack ARIA.

---

*End of Report*
