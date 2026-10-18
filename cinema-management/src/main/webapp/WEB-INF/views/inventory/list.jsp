<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="ctx" value="${pageContext.request.contextPath}" scope="request"/>
<c:set var="currentRole" value="${empty sessionScope.role ? 'GUEST' : sessionScope.role}"/>
<c:set var="activeNav" value="inventory" scope="request"/>
<c:set var="pageTitle" value="Quản lý tồn kho" scope="request"/>
<c:set var="pageEyebrow" value="KHU VỰC QUẢN LÝ CINEMAHUB" scope="request"/>

<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <meta name="ctx" content="${ctx}">
    <meta name="user-role" content="${currentRole}">
    <meta name="csrf-token" content="${sessionScope.csrfToken}">
    <title>Quản lý tồn kho | Khu vực quản lý CinemaHub</title>
    <link rel="stylesheet" href="${ctx}/assets/css/cinema.css">
    <link rel="stylesheet" href="${ctx}/assets/css/workspace.css?v=20261016-inv-accordion-1">
    <style>
        /* Đảm bảo giao diện ăn khớp 100% chuẩn Workspace */
        header.nav-wrapper, .nav-wrapper, footer { display: none !important; }
        body.workspace-page { background: var(--ws-bg, #f5f7fb) !important; color: var(--ws-text, #1f2937); }
        .ws-main { margin-left: var(--ws-sidebar-w, 256px) !important; min-height: 100vh; }
        .ws-main-inner { padding: 28px 28px 40px !important; max-width: 1500px !important; margin: 0 auto !important; }
        
        /* Modal chuẩn Workspace */
        .ws-modal-hidden { display: none !important; }
        .ws-modal-form-group { margin-bottom: 16px; }
        .ws-modal-form-group label {
            display: block;
            margin-bottom: 6px;
            font-size: 0.82rem;
            font-weight: 600;
            color: var(--ws-text-muted, #6b7280);
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .ws-modal-form-group input,
        .ws-modal-form-group select,
        .ws-modal-form-group textarea {
            width: 100%;
            box-sizing: border-box;
            padding: 9px 12px;
            border: 1px solid var(--ws-line-strong, #d8dce3);
            border-radius: var(--ws-radius-sm, 8px);
            background: #fff !important;
            color: var(--ws-text, #1f2937) !important;
            font-size: 0.9rem;
        }
        .ws-modal-form-group input:focus,
        .ws-modal-form-group select:focus,
        .ws-modal-form-group textarea:focus {
            border-color: var(--ws-accent, #2563eb);
            outline: none;
            box-shadow: 0 0 0 3px var(--ws-accent-soft, #eff6ff);
        }
        .ws-modal-foot {
            display: flex;
            justify-content: flex-end;
            gap: 10px;
            padding: 16px 20px;
            border-top: 1px solid var(--ws-line, #e5e7eb);
            background: #fafbfc;
            border-radius: 0 0 var(--ws-radius, 12px) var(--ws-radius, 12px);
        }
    </style>
</head>
<body class="workspace-page">
    <%-- Sidebar Quản lý đồng bộ hệ thống --%>
    <%@ include file="/WEB-INF/views/layout/workspace-sidebar.jsp" %>

    <main class="ws-main" id="wsMain">
        <%-- Top Header Quản lý --%>
        <%@ include file="/WEB-INF/views/layout/workspace-header.jsp" %>

        <div class="ws-main-inner">
            <%-- Tiêu đề trang đồng bộ 100% với Bảng điều khiển (ảnh 2) --%>
            <div class="ws-page-heading">
                <span class="eyebrow">WORKSPACE</span>
                <h1>Quản lý tồn kho</h1>
                <p class="muted">Theo dõi số lượng hàng hóa và lịch sử biến động kho theo từng chi nhánh.</p>
            </div>

            <%-- Thanh công cụ (Toolbar) chuẩn Workspace --%>
            <div class="ws-list-toolbar">
                <div class="ws-list-toolbar-left">
                    <select id="branchSelect" class="ws-select" aria-label="Chọn chi nhánh" style="min-width: 220px; font-weight: 500;"></select>
                    
                    <div class="ws-search">
                        <span aria-hidden="true">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
                        </span>
                        <input type="search" id="searchInput" placeholder="Tìm sản phẩm theo tên…" aria-label="Tìm sản phẩm">
                    </div>

                    <select id="categoryFilter" class="ws-select" aria-label="Lọc theo loại">
                        <option value="">Tất cả loại sản phẩm</option>
                        <option value="POPCORN">Bắp (POPCORN)</option>
                        <option value="DRINK">Nước uống (DRINK)</option>
                        <option value="COMBO">Combo</option>
                        <option value="OTHER">Khác</option>
                    </select>
                </div>

                <div class="ws-list-toolbar-right">
                    <button id="btnOpenRestock" type="button" class="ws-btn primary">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                        Nhập kho mới
                    </button>
                    <button id="btnOpenAdjust" type="button" class="ws-btn secondary">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                        Điều chỉnh kho
                    </button>
                </div>
            </div>

            <%-- Bảng tồn kho dạng Card chuẩn Workspace --%>
            <div class="ws-card" style="padding:0; overflow:hidden;">
                <div class="ws-table-wrap">
                    <table class="ws-table">
                        <thead>
                            <tr>
                                <th style="min-width:220px">Sản phẩm</th>
                                <th>Loại</th>
                                <th>Đơn giá</th>
                                <th>Số lượng tồn</th>
                                <th>Trạng thái</th>
                                <th class="ws-th-actions" style="text-align:right">Thao tác</th>
                            </tr>
                        </thead>
                        <tbody id="inventoryTableBody">
                            <tr>
                                <td colspan="6" style="padding:48px 20px; text-align:center;">
                                    <div class="ws-list-loading">
                                        <div class="ws-spinner"></div>
                                        <span>Đang tải dữ liệu tồn kho…</span>
                                    </div>
                                </td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    </main>

    <%-- MODAL LỊCH SỬ BIẾN ĐỘNG (M-P2) --%>
    <div id="modalHistory" class="ws-modal ws-modal-hidden">
        <div class="ws-modal-backdrop" onclick="closeModal('modalHistory')"></div>
        <div class="ws-modal-panel" style="max-width: 720px;">
            <div class="ws-modal-head">
                <h2 id="historyTitle">Lịch sử biến động tồn kho</h2>
                <button type="button" class="ws-modal-x" onclick="closeModal('modalHistory')" aria-label="Đóng">&times;</button>
            </div>
            <div class="ws-modal-body" style="padding:0;">
                <table class="ws-table" style="margin:0;">
                    <thead>
                        <tr>
                            <th>Thời gian</th>
                            <th>Biến động</th>
                            <th>Lý do</th>
                            <th>Người thực hiện</th>
                        </tr>
                    </thead>
                    <tbody id="historyTableBody">
                        <tr><td colspan="4" style="text-align:center; padding:30px; color:var(--ws-text-muted);">Đang tải lịch sử…</td></tr>
                    </tbody>
                </table>
            </div>
            <div class="ws-modal-foot">
                <button type="button" class="ws-btn secondary" onclick="closeModal('modalHistory')">Đóng</button>
            </div>
        </div>
    </div>

    <%-- MODAL NHẬP KHO (M-P3) --%>
    <div id="modalRestock" class="ws-modal ws-modal-hidden">
        <div class="ws-modal-backdrop" onclick="closeModal('modalRestock')"></div>
        <div class="ws-modal-panel" style="max-width: 500px;">
            <div class="ws-modal-head">
                <h2>Nhập kho sản phẩm</h2>
                <button type="button" class="ws-modal-x" onclick="closeModal('modalRestock')" aria-label="Đóng">&times;</button>
            </div>
            <div class="ws-modal-body">
                <div class="ws-modal-form-group">
                    <label for="restockProductSelect">Chọn sản phẩm</label>
                    <select id="restockProductSelect" class="ws-select" style="width:100%"></select>
                </div>
                <div class="ws-modal-form-group">
                    <label for="restockQty">Số lượng nhập</label>
                    <input type="number" id="restockQty" min="1" value="10" placeholder="Nhập số lượng">
                </div>
            </div>
            <div class="ws-modal-foot">
                <button type="button" class="ws-btn secondary" onclick="closeModal('modalRestock')">Hủy</button>
                <button type="button" class="ws-btn primary" id="btnSubmitRestock">Xác nhận nhập kho</button>
            </div>
        </div>
    </div>

    <%-- MODAL ĐIỀU CHỈNH KHO (M4) --%>
    <div id="modalAdjust" class="ws-modal ws-modal-hidden">
        <div class="ws-modal-backdrop" onclick="closeModal('modalAdjust')"></div>
        <div class="ws-modal-panel" style="max-width: 440px; border-radius: 12px; overflow: hidden; padding: 0;">
            <div style="background: #1d4ed8; color: #ffffff; padding: 16px 20px; display: flex; justify-content: space-between; align-items: flex-start;">
                <div>
                    <h3 style="margin: 0; font-size: 1.08rem; font-weight: 700; color: #fff; line-height: 1.3;">M4. Điều chỉnh kho</h3>
                    <small style="font-size: 0.82rem; color: #dbeafe; display: block; margin-top: 2px;">(Tăng/Giảm tồn kho)</small>
                </div>
                <button type="button" onclick="closeModal('modalAdjust')" aria-label="Đóng" style="color: #fff; background: rgba(255,255,255,0.18); border-radius: 50%; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; font-size: 18px; cursor: pointer;">&times;</button>
            </div>
            <div style="padding: 20px; background: #fff;">
                <h4 style="margin: 0 0 16px; font-size: 1.15rem; font-weight: 700; color: #0f172a;">Điều chỉnh kho</h4>

                <!-- 1. Sản phẩm * -->
                <div style="margin-bottom: 12px;">
                    <label for="adjustProductSelect" style="display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;">
                        Sản phẩm <span style="color: #ef4444;">*</span>
                    </label>
                    <select id="adjustProductSelect" class="ws-select" style="width: 100%; box-sizing: border-box; height: 38px;">
                        <option value="">Chọn sản phẩm…</option>
                    </select>
                </div>

                <!-- 2. Tồn hiện tại: X -->
                <div style="margin-bottom: 16px; font-size: 0.9rem; color: #475569;">
                    Tồn hiện tại: <strong id="adjustCurrentQtyText" style="font-size: 1.05rem; color: #0f172a;">0</strong>
                </div>

                <!-- 3. Loại điều chỉnh * -->
                <div style="margin-bottom: 16px;">
                    <label style="display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 8px;">
                        Loại điều chỉnh <span style="color: #ef4444;">*</span>
                    </label>
                    <div style="display: flex; gap: 24px; align-items: center;">
                        <label style="display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-weight: 600; color: #16a34a; font-size: 0.95rem;">
                            <input type="radio" name="adjustTypeRadio" value="PLUS" checked style="accent-color: #16a34a; width: 17px; height: 17px; cursor: pointer;">
                            🟢 Tăng
                        </label>
                        <label style="display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-weight: 600; color: #dc2626; font-size: 0.95rem;">
                            <input type="radio" name="adjustTypeRadio" value="MINUS" style="accent-color: #dc2626; width: 17px; height: 17px; cursor: pointer;">
                            🔴 Giảm
                        </label>
                    </div>
                </div>

                <!-- 4. Số lượng * -->
                <div style="margin-bottom: 16px;">
                    <label for="adjustQty" style="display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;">
                        Số lượng <span style="color: #ef4444;">*</span>
                    </label>
                    <input type="number" id="adjustQty" min="1" placeholder="Nhập số lượng (> 0)" style="width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;">
                </div>

                <!-- 5. Lý do * -->
                <div style="margin-bottom: 16px;">
                    <label for="adjustReasonSelect" style="display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;">
                        Lý do <span style="color: #ef4444;">*</span>
                    </label>
                    <select id="adjustReasonSelect" class="ws-select" style="width: 100%; box-sizing: border-box; height: 38px;">
                        <option value="">Chọn lý do…</option>
                        <option value="Kiểm kê định kỳ">Kiểm kê định kỳ</option>
                        <option value="Hư hỏng / Rách vỡ">Hư hỏng / Rách vỡ</option>
                        <option value="Hết hạn sử dụng">Hết hạn sử dụng</option>
                        <option value="Hàng khuyến mãi / Quà tặng">Hàng khuyến mãi / Quà tặng</option>
                        <option value="Nhập nhầm số liệu">Nhập nhầm số liệu</option>
                        <option value="Khác">Khác</option>
                    </select>
                </div>

                <!-- 6. Ghi chú -->
                <div style="margin-bottom: 6px;">
                    <label for="adjustNoteInput" style="display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;">Ghi chú</label>
                    <input type="text" id="adjustNoteInput" placeholder="Nhập ghi chú (nếu có)…" style="width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;">
                </div>

                <!-- 7. Buttons Footer -->
                <div style="display: flex; gap: 12px; margin-top: 24px;">
                    <button type="button" onclick="closeModal('modalAdjust')" style="flex: 1; padding: 10px 16px; border-radius: 8px; border: 1px solid #cbd5e1; background: #e2e8f0; color: #334155; font-weight: 600; font-size: 0.95rem; cursor: pointer;">Hủy</button>
                    <button type="button" id="btnSubmitAdjust" style="flex: 1; padding: 10px 16px; border-radius: 8px; border: none; background: #1d4ed8; color: #ffffff; font-weight: 600; font-size: 0.95rem; cursor: pointer;">Xác nhận</button>
                </div>
            </div>
        </div>
    </div>

    <script src="${ctx}/assets/js/app.js?v=20261004-showtime-reminders-1" defer></script>
    <script>
    function openModal(id) {
        var m = document.getElementById(id);
        if (m) m.classList.remove('ws-modal-hidden');
    }
    function closeModal(id) {
        var m = document.getElementById(id);
        if (m) m.classList.add('ws-modal-hidden');
    }

    (function() {
        var ctx = '${ctx}';
        var stockData = [];

        var branchSelect = document.getElementById('branchSelect');
        var searchInput = document.getElementById('searchInput');
        var categoryFilter = document.getElementById('categoryFilter');

        searchInput.addEventListener('input', renderTable);
        categoryFilter.addEventListener('change', renderTable);
        branchSelect.addEventListener('change', function() {
            localStorage.setItem('cinemahub_last_branch', this.value);
            loadStock(this.value);
        });

        // 1. Tải danh sách chi nhánh
        function loadBranches() {
            fetch(ctx + '/branch')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    var list = res.data || res || [];
                    branchSelect.innerHTML = '';
                    var lastBranch = localStorage.getItem('cinemahub_last_branch');
                    var selectedId = null;

                    list.forEach(function(b) {
                        var opt = document.createElement('option');
                        opt.value = b.id;
                        opt.textContent = '🏢 Chi nhánh: ' + b.name;
                        if (String(b.id) === String(lastBranch)) {
                            opt.selected = true;
                            selectedId = b.id;
                        }
                        branchSelect.appendChild(opt);
                    });

                    if (!selectedId && list.length > 0) {
                        selectedId = list[0].id;
                    }
                    if (selectedId) {
                        branchSelect.value = selectedId;
                        loadStock(selectedId);
                    }
                })
                .catch(function(err) {
                    console.error('Lỗi khi tải chi nhánh:', err);
                });
        }

        // 2. Tải tồn kho
        function loadStock(branchId) {
            var tbody = document.getElementById('inventoryTableBody');
            tbody.innerHTML = '<tr><td colspan="6" style="padding:48px 20px; text-align:center;">' +
                '<div class="ws-list-loading"><div class="ws-spinner"></div><span>Đang tải dữ liệu tồn kho…</span></div>' +
                '</td></tr>';

            fetch(ctx + '/inventory?branchId=' + branchId)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    stockData = res.data || res || [];
                    renderTable();
                    populateProductSelects();
                })
                .catch(function(err) {
                    console.error('Lỗi khi tải tồn kho:', err);
                    tbody.innerHTML = '<tr><td colspan="6" style="padding:32px; text-align:center; color:var(--ws-danger);">' +
                        'Không thể tải dữ liệu tồn kho. Vui lòng thử lại sau.</td></tr>';
                });
        }

        // 3. Render bảng danh sách theo chuẩn Workspace
        function renderTable() {
            var tbody = document.getElementById('inventoryTableBody');
            var search = searchInput.value.toLowerCase().trim();
            var cat = categoryFilter.value;

            var filtered = stockData.filter(function(item) {
                var matchSearch = !search || (item.name && item.name.toLowerCase().includes(search));
                var matchCat = !cat || item.type === cat;
                return matchSearch && matchCat;
            });

            if (filtered.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" style="padding:0; background:transparent;">' +
                    '<div class="ws-empty">' +
                        '<div class="ws-empty-icon">' +
                            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="28" height="28">' +
                                '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>' +
                            '</svg>' +
                        '</div>' +
                        '<h3>Chưa có dữ liệu tồn kho</h3>' +
                        '<p>Chi nhánh này chưa có sản phẩm nào. Bạn có thể chọn chi nhánh khác hoặc nhập kho mới.</p>' +
                        '<button type="button" class="ws-btn primary" onclick="openModal(\'modalRestock\')">+ Nhập kho ngay</button>' +
                    '</div>' +
                '</td></tr>';
                return;
            }

            tbody.innerHTML = filtered.map(function(item) {
                var qty = item.qty != null ? item.qty : (item.quantity != null ? item.quantity : 0);
                var pId = item.productId || item.id;
                
                var badgeTone = qty <= 0 ? 'red' : (qty < 10 ? 'orange' : 'green');
                var badgeText = qty <= 0 ? 'Hết hàng' : (qty < 10 ? ('Sắp hết (' + qty + ')') : 'Còn hàng');
                var badgeHtml = '<span class="ws-badge ws-status-pill ws-badge-' + badgeTone + '">' + badgeText + '</span>';

                var priceText = item.price ? Number(item.price).toLocaleString('vi-VN') + ' đ' : '—';

                return '<tr>' +
                    '<td><strong>' + (item.name || ('Sản phẩm #' + pId)) + '</strong>' +
                        '<br><span class="sub muted" style="font-size:0.8rem; color:var(--ws-text-muted)">Mã: #' + pId + '</span></td>' +
                    '<td><span class="sub muted">' + (item.type || item.category || '—') + '</span></td>' +
                    '<td style="font-weight:600; color:var(--ws-text-strong)">' + priceText + '</td>' +
                    '<td><strong style="font-size:1rem;">' + qty + '</strong></td>' +
                    '<td>' + badgeHtml + '</td>' +
                    '<td>' +
                        '<div class="row-actions">' +
                            '<button type="button" class="ws-btn ws-btn-sm secondary" onclick="showHistory(' + pId + ', \'' + (item.name ? item.name.replace(/'/g, "\\'") : '') + '\')">Lịch sử</button>' +
                            '<button type="button" class="ws-btn ws-btn-sm secondary" onclick="quickRestock(' + pId + ')">Nhập</button>' +
                            '<button type="button" class="ws-btn ws-btn-sm danger" onclick="openAdjustModal(' + pId + ')">Điều chỉnh</button>' +
                        '</div>' +
                    '</td>' +
                '</tr>';
            }).join('');
        }

        function updateAdjustCurrentQty() {
            var aSel = document.getElementById('adjustProductSelect');
            var selectedOpt = aSel.options[aSel.selectedIndex];
            var qty = selectedOpt ? (selectedOpt.getAttribute('data-qty') || 0) : 0;
            document.getElementById('adjustCurrentQtyText').textContent = qty;
        }
        document.getElementById('adjustProductSelect').addEventListener('change', updateAdjustCurrentQty);

        // 4. Nạp option cho select trong modal
        function populateProductSelects() {
            var rSel = document.getElementById('restockProductSelect');
            var aSel = document.getElementById('adjustProductSelect');
            var opts = stockData.map(function(item) {
                var pId = item.productId || item.id;
                var qty = item.qty != null ? item.qty : (item.quantity != null ? item.quantity : 0);
                return '<option value="' + pId + '" data-qty="' + qty + '">' + (item.name || ('Sản phẩm #' + pId)) + ' (' + qty + ')</option>';
            }).join('');
            rSel.innerHTML = '<option value="">Chọn sản phẩm…</option>' + opts;
            aSel.innerHTML = '<option value="">Chọn sản phẩm…</option>' + opts;
            updateAdjustCurrentQty();
        }

        // Phím tắt nạp nhanh vào modal
        window.quickRestock = function(productId) {
            document.getElementById('restockProductSelect').value = productId;
            openModal('modalRestock');
        };

        window.openAdjustModal = function(productId) {
            if (productId) {
                document.getElementById('adjustProductSelect').value = String(productId);
            }
            updateAdjustCurrentQty();
            openModal('modalAdjust');
        };

        // 5. Xem lịch sử biến động (M-P2)
        window.showHistory = function(productId, productName) {
            var branchId = branchSelect.value;
            document.getElementById('historyTitle').textContent = 'Lịch sử biến động: ' + (productName || ('Sản phẩm #' + productId));
            openModal('modalHistory');
            var tbody = document.getElementById('historyTableBody');
            tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; padding:30px; color:var(--ws-text-muted);">Đang tải lịch sử…</td></tr>';

            fetch(ctx + '/inventory?action=history&branchId=' + branchId + '&productId=' + productId)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    var list = res.data || res || [];
                    if (!Array.isArray(list) || list.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; padding:30px; color:var(--ws-text-muted);">Chưa có lịch sử biến động nào cho sản phẩm này.</td></tr>';
                        return;
                    }
                    function formatReasonText(reason) {
                        if (!reason) return '—';
                        var str = String(reason).trim();
                        var map = {
                            'NHAP_HANG_TU_NCC': 'Nhập hàng từ NCC',
                            'NHAP_LAI_TU_NCC': 'Nhập đổi trả từ NCC',
                            'NHAP_KHO': 'Nhập hàng vào kho',
                            'RESTOCK': 'Nhập hàng',
                            'KIEM_KE_THUA': 'Kiểm kê thừa',
                            'KIEM_KE_THIEU': 'Kiểm kê thiếu',
                            'HU_HONG': 'Hư hỏng / Rách bao bì',
                            'HET_HAN': 'Hết hạn sử dụng',
                            'MAT_DO_KHACH': 'Thất thoát / Đổ vỡ',
                            'XUAT_CHE_BIEN': 'Xuất chế biến',
                            'NHAP_TU_CHE_BIEN': 'Nhập từ chế biến',
                            'XUAT_BAN': 'Xuất bán tại quầy',
                            'BAN_HANG': 'Xuất bán tại quầy',
                            'OTHER': 'Lý do khác',
                            'ADJUST': 'Điều chỉnh kho'
                        };
                        var dashIdx = str.indexOf(' - ');
                        if (dashIdx > 0) {
                            var code = str.substring(0, dashIdx).trim().toUpperCase();
                            var detail = str.substring(dashIdx + 3).trim();
                            var label = map[code] || code;
                            return '<div style="font-weight:600; color:#1e293b;">' + label + '</div>' + (detail ? ('<div style="color:#64748b; font-size:0.85rem; margin-top:2px;">' + detail + '</div>') : '');
                        }
                        return map[str.toUpperCase()] || str;
                    }

                    tbody.innerHTML = list.map(function(h) {
                        var delta = h.deltaQty > 0 ? ('+' + h.deltaQty) : h.deltaQty;
                        var badgeClass = h.deltaQty > 0 ? 'ws-badge-green' : 'ws-badge-red';
                        return '<tr>' +
                            '<td style="color:var(--ws-text-muted); font-size:0.85rem;">' + (h.createdAt || '—') + '</td>' +
                            '<td><span class="ws-badge ws-status-pill ' + badgeClass + '">' + delta + '</span></td>' +
                            '<td>' + formatReasonText(h.reason) + '</td>' +
                            '<td><strong>' + (h.actorName || ('ID: ' + h.actorId)) + '</strong></td>' +
                        '</tr>';
                    }).join('');
                })
                .catch(function(err) {
                    console.error('Lỗi tải lịch sử:', err);
                    tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; padding:30px; color:var(--ws-danger);">Không thể tải lịch sử biến động.</td></tr>';
                });
        };

        // Mở modal từ nút toolbar
        document.getElementById('btnOpenRestock').onclick = function() { openModal('modalRestock'); };
        document.getElementById('btnOpenAdjust').onclick = function() { openAdjustModal(); };

        // 6. Xử lý Nhập kho (M-P3)
        document.getElementById('btnSubmitRestock').onclick = function() {
            var bId = branchSelect.value;
            var pId = document.getElementById('restockProductSelect').value;
            var qty = parseInt(document.getElementById('restockQty').value, 10);
            if (!pId) { alert('Vui lòng chọn sản phẩm.'); return; }
            if (isNaN(qty) || qty <= 0) { alert('Số lượng nhập phải lớn hơn 0.'); return; }

            fetch(ctx + '/inventory?branchId=' + bId + '&productId=' + pId + '&quantity=' + qty, { method: 'POST' })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res.code && res.code !== 'OK' && res.code !== 200) {
                        alert('Lỗi: ' + (res.message || res.code));
                    } else {
                        alert('Nhập kho thành công!');
                        closeModal('modalRestock');
                        loadStock(bId);
                    }
                })
                .catch(function(err) {
                    alert('Lỗi kết nối khi nhập kho: ' + err);
                });
        };

        // 7. Xử lý Điều chỉnh kho (M4)
        document.getElementById('btnSubmitAdjust').onclick = function() {
            var bId = branchSelect.value;
            var pId = document.getElementById('adjustProductSelect').value;
            var isMinus = (document.querySelector('input[name="adjustTypeRadio"]:checked') || {}).value === 'MINUS';
            var qty = parseInt(document.getElementById('adjustQty').value, 10);
            var reason = document.getElementById('adjustReasonSelect').value;
            var note = (document.getElementById('adjustNoteInput').value || '').trim();
            var currentQty = parseInt(document.getElementById('adjustCurrentQtyText').textContent, 10) || 0;

            if (!pId) { alert('Vui lòng chọn sản phẩm.'); return; }
            if (isNaN(qty) || qty <= 0) { alert('Vui lòng nhập số lượng hợp lệ (> 0).'); return; }
            if (isMinus && qty > currentQty) {
                var proceed = confirm('Số lượng giảm (' + qty + ') lớn hơn tồn kho hiện tại (' + currentQty + '). Bạn có chắc chắn muốn điều chỉnh?');
                if (!proceed) return;
            }
            if (!reason) { alert('Vui lòng chọn lý do.'); return; }

            var finalReason = reason + (note ? (' - ' + note) : '');
            var finalQty = isMinus ? -qty : qty;

            var btn = document.getElementById('btnSubmitAdjust');
            btn.disabled = true;
            btn.textContent = 'Đang lưu…';

            fetch(ctx + '/inventory', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({
                    action: 'adjust',
                    branchId: bId,
                    productId: pId,
                    quantity: finalQty,
                    reason: finalReason
                })
            })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    btn.disabled = false;
                    btn.textContent = 'Xác nhận';
                    if (res.code && res.code !== 'OK' && res.code !== 200) {
                        alert('Lỗi: ' + (res.message || res.code));
                    } else {
                        alert('Điều chỉnh kho thành công!');
                        closeModal('modalAdjust');
                        loadStock(bId);
                    }
                })
                .catch(function(err) {
                    btn.disabled = false;
                    btn.textContent = 'Xác nhận';
                    alert('Lỗi kết nối khi điều chỉnh kho: ' + err);
                });
        };

        // Khởi động
        loadBranches();
    })();
    </script>
</body>
</html>
