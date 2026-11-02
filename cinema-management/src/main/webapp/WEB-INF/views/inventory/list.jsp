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
    <link rel="stylesheet" href="${ctx}/assets/css/workspace.css?v=20261004-branch-pricing-templates-1">
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
                <p class="muted">Theo dõi số lượng hàng hóa, nguyên liệu thô và công thức chế biến theo từng chi nhánh.</p>
            </div>

            <%-- Tabs: Kho thành phẩm | Kho nguyên liệu | Công thức chế biến --%>
            <div class="ws-tabs" style="display:flex; gap:6px; border-bottom:1px solid var(--ws-line, #e5e7eb); margin-bottom:18px;">
                <button type="button" class="ws-tab ws-tab-active" data-tab="tab-finished"
                    style="padding:10px 18px; background:transparent; border:none; border-bottom:2px solid var(--ws-accent, #d9363e); font-weight:600; color:var(--ws-accent, #d9363e); cursor:pointer;">
                    Kho thành phẩm
                </button>
                <button type="button" class="ws-tab" data-tab="tab-material"
                    style="padding:10px 18px; background:transparent; border:none; border-bottom:2px solid transparent; font-weight:600; color:var(--ws-text-muted, #6b7280); cursor:pointer;">
                    Kho nguyên liệu
                </button>
                <button type="button" class="ws-tab" data-tab="tab-recipe"
                    style="padding:10px 18px; background:transparent; border:none; border-bottom:2px solid transparent; font-weight:600; color:var(--ws-text-muted, #6b7280); cursor:pointer;">
                    Công thức chế biến
                </button>
            </div>

            <%-- TAB 1: KHO THÀNH PHẨM --%>
            <div id="tab-finished" class="ws-tab-pane">

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
            </div>
            <%-- /TAB 1: KHO THÀNH PHẨM --%>

            <%-- TAB 2: KHO NGUYÊN LIỆU (GIAI ĐOẠN 2) --%>
            <div id="tab-material" class="ws-tab-pane" style="display:none;">
                <div class="ws-list-toolbar" style="margin-bottom:14px;">
                    <div class="ws-list-toolbar-left">
                        <div class="ws-search">
                            <span aria-hidden="true">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
                            </span>
                            <input type="search" id="materialSearchInput" placeholder="Tìm nguyên liệu theo tên / mã…" aria-label="Tìm nguyên liệu">
                        </div>
                        <select id="materialCategoryFilter" class="ws-select" aria-label="Lọc theo loại">
                            <option value="">Tất cả loại</option>
                            <option value="POPCORN">Bắp (POPCORN)</option>
                            <option value="DRINK">Nước (DRINK)</option>
                            <option value="PACKAGING">Bao bì (PACKAGING)</option>
                            <option value="INGREDIENT">Thành phần (INGREDIENT)</option>
                            <option value="OTHER">Khác</option>
                        </select>
                    </div>
                    <div class="ws-list-toolbar-right">
                        <button id="btnOpenRestockMaterial" type="button" class="ws-btn primary">+ Nhập nguyên liệu</button>
                        <button id="btnOpenAdjustMaterial" type="button" class="ws-btn secondary">Điều chỉnh tồn</button>
                    </div>
                </div>
                <div class="ws-card" style="padding:0; overflow:hidden;">
                    <div class="ws-table-wrap">
                        <table class="ws-table">
                            <thead>
                                <tr>
                                    <th style="min-width:200px">Nguyên liệu</th>
                                    <th>Mã</th>
                                    <th>Loại</th>
                                    <th>Đơn vị</th>
                                    <th>Tồn kho</th>
                                    <th>Tồn tối thiểu</th>
                                    <th>Nhà cung cấp</th>
                                    <th class="ws-th-actions" style="text-align:right">Thao tác</th>
                                </tr>
                            </thead>
                            <tbody id="materialTableBody">
                                <tr><td colspan="8" style="padding:32px; text-align:center; color:var(--ws-text-muted);">Đang tải dữ liệu nguyên liệu…</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
            <%-- /TAB 2 --%>

            <%-- TAB 3: CÔNG THỨC CHẾ BIẾN (GIAI ĐOẠN 3) --%>
            <div id="tab-recipe" class="ws-tab-pane" style="display:none;">
                <div class="ws-list-toolbar" style="margin-bottom:14px;">
                    <div class="ws-list-toolbar-left">
                        <select id="recipeProductSelect" class="ws-select" aria-label="Chọn sản phẩm" style="min-width:240px;"></select>
                        <input type="search" id="recipeSearch" class="ws-input" placeholder="Lọc nguyên liệu trong công thức…" style="padding:8px 12px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:8px; min-width:240px;">
                    </div>
                    <div class="ws-list-toolbar-right">
                        <button id="btnOpenProduce" type="button" class="ws-btn primary">+ Chế biến / Sản xuất</button>
                        <button id="btnAddRecipeItem" type="button" class="ws-btn secondary">+ Thêm nguyên liệu</button>
                        <button id="btnSaveRecipe" type="button" class="ws-btn primary">💾 Lưu công thức</button>
                    </div>
                </div>
                <div class="ws-card" style="padding:0; overflow:hidden;">
                    <div class="ws-table-wrap">
                        <table class="ws-table">
                            <thead>
                                <tr>
                                    <th>Mã NL</th>
                                    <th>Tên nguyên liệu</th>
                                    <th>Định mức (cho 1 thành phẩm)</th>
                                    <th>Đơn vị</th>
                                    <th>Hao hụt (%)</th>
                                    <th>Ghi chú</th>
                                    <th class="ws-th-actions" style="text-align:right">Thao tác</th>
                                </tr>
                            </thead>
                            <tbody id="recipeTableBody">
                                <tr><td colspan="7" style="padding:32px; text-align:center; color:var(--ws-text-muted);">Chọn sản phẩm để xem / sửa công thức.</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
            <%-- /TAB 3 --%>
        </main>

    <%-- MODAL LỊCH SỬ BIẾN ĐỘNG NGUYÊN LIỆU --%>
    <div id="modalMaterialHistory" class="ws-modal ws-modal-hidden">
        <div class="ws-modal-backdrop" onclick="closeModal('modalMaterialHistory')"></div>
        <div class="ws-modal-panel" style="max-width: 820px;">
            <div class="ws-modal-head">
                <h2 id="materialHistoryTitle">Lịch sử biến động nguyên liệu</h2>
                <button type="button" class="ws-modal-x" onclick="closeModal('modalMaterialHistory')" aria-label="Đóng">&times;</button>
            </div>
            <div class="ws-modal-body" style="padding:0;">
                <table class="ws-table" style="margin:0;">
                    <thead>
                        <tr>
                            <th>Thời gian</th>
                            <th>Biến động</th>
                            <th>Lý do</th>
                            <th>Ghi chú / NCC / PO</th>
                            <th>Người thực hiện</th>
                        </tr>
                    </thead>
                    <tbody id="materialHistoryTableBody">
                        <tr><td colspan="5" style="text-align:center; padding:30px; color:var(--ws-text-muted);">Đang tải lịch sử…</td></tr>
                    </tbody>
                </table>
            </div>
            <div class="ws-modal-foot">
                <button type="button" class="ws-btn secondary" onclick="closeModal('modalMaterialHistory')">Đóng</button>
            </div>
        </div>
    </div>

    <%-- MODAL NHẬP KHO NGUYÊN LIỆU (M-R3) --%>
    <div id="modalRestockMaterial" class="ws-modal ws-modal-hidden">
        <div class="ws-modal-backdrop" onclick="closeModal('modalRestockMaterial')"></div>
        <div class="ws-modal-panel" style="max-width: 560px;">
            <div class="ws-modal-head">
                <h2>Nhập kho nguyên liệu</h2>
                <button type="button" class="ws-modal-x" onclick="closeModal('modalRestockMaterial')" aria-label="Đóng">&times;</button>
            </div>
            <div class="ws-modal-body">
                <div class="ws-modal-form-group">
                    <label for="restockMaterialSelect">Chọn nguyên liệu</label>
                    <select id="restockMaterialSelect" class="ws-select" style="width:100%"></select>
                </div>
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px;">
                    <div class="ws-modal-form-group">
                        <label for="restockMaterialQty">Số lượng nhập</label>
                        <input type="number" id="restockMaterialQty" min="0.001" step="0.001" value="1">
                    </div>
                    <div class="ws-modal-form-group">
                        <label for="restockMaterialReason">Lý do nhập</label>
                        <input type="text" id="restockMaterialReason" placeholder="NHAP_HANG_TU_NCC" value="NHAP_HANG_TU_NCC">
                    </div>
                </div>
                <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:12px;">
                    <div class="ws-modal-form-group">
                        <label for="restockMaterialCost">Giá nhập (đ)</label>
                        <input type="number" id="restockMaterialCost" min="0" step="100" placeholder="0">
                    </div>
                    <div class="ws-modal-form-group">
                        <label for="restockMaterialSupplier">Nhà cung cấp</label>
                        <input type="text" id="restockMaterialSupplier" placeholder="NCC">
                    </div>
                    <div class="ws-modal-form-group">
                        <label for="restockMaterialPo">Số PO</label>
                        <input type="text" id="restockMaterialPo" placeholder="PO-...">
                    </div>
                </div>
                <div class="ws-modal-form-group">
                    <label for="restockMaterialNote">Ghi chú</label>
                    <input type="text" id="restockMaterialNote" placeholder="Lô hàng, hạn dùng...">
                </div>
            </div>
            <div class="ws-modal-foot">
                <button type="button" class="ws-btn secondary" onclick="closeModal('modalRestockMaterial')">Hủy</button>
                <button type="button" class="ws-btn primary" id="btnSubmitRestockMaterial">Xác nhận nhập</button>
            </div>
        </div>
    </div>

    <%-- MODAL ĐIỀU CHỈNH NGUYÊN LIỆU (M-R5) --%>
    <div id="modalAdjustMaterial" class="ws-modal ws-modal-hidden">
        <div class="ws-modal-backdrop" onclick="closeModal('modalAdjustMaterial')"></div>
        <div class="ws-modal-panel" style="max-width: 520px;">
            <div class="ws-modal-head">
                <h2>Điều chỉnh tồn kho nguyên liệu</h2>
                <button type="button" class="ws-modal-x" onclick="closeModal('modalAdjustMaterial')" aria-label="Đóng">&times;</button>
            </div>
            <div class="ws-modal-body">
                <div class="ws-modal-form-group">
                    <label for="adjustMaterialSelect">Chọn nguyên liệu</label>
                    <select id="adjustMaterialSelect" class="ws-select" style="width:100%"></select>
                </div>
                <div class="ws-modal-form-group">
                    <label>Hướng điều chỉnh</label>
                    <div style="display:flex; gap:14px; padding-top:4px;">
                        <label style="display:flex; align-items:center; gap:6px;"><input type="radio" name="adjustMatDir" value="PLUS" checked> Tăng</label>
                        <label style="display:flex; align-items:center; gap:6px;"><input type="radio" name="adjustMatDir" value="MINUS"> Giảm</label>
                    </div>
                </div>
                <div class="ws-modal-form-group">
                    <label for="adjustMaterialQty">Số lượng điều chỉnh (lớn hơn 0)</label>
                    <input type="number" id="adjustMaterialQty" min="0.001" step="0.001" value="1">
                </div>
                <div class="ws-modal-form-group">
                    <label for="adjustMaterialReason">Lý do <span style="color:#ef4444">*</span></label>
                    <input type="text" id="adjustMaterialReason" placeholder="Hao hụt, hư hỏng, sai lệch kiểm đếm..." required>
                </div>
                <div class="ws-modal-form-group">
                    <label for="adjustMaterialNote">Ghi chú</label>
                    <input type="text" id="adjustMaterialNote">
                </div>
            </div>
            <div class="ws-modal-foot">
                <button type="button" class="ws-btn secondary" onclick="closeModal('modalAdjustMaterial')">Hủy</button>
                <button type="button" class="ws-btn primary" id="btnSubmitAdjustMaterial">Xác nhận</button>
            </div>
        </div>
    </div>

    <%-- MODAL CHẾ BIẾN / SẢN XUẤT (M-R4) --%>
    <div id="modalProduce" class="ws-modal ws-modal-hidden">
        <div class="ws-modal-backdrop" onclick="closeModal('modalProduce')"></div>
        <div class="ws-modal-panel" style="max-width: 520px;">
            <div class="ws-modal-head">
                <h2>Chế biến / Sản xuất thành phẩm</h2>
                <button type="button" class="ws-modal-x" onclick="closeModal('modalProduce')" aria-label="Đóng">&times;</button>
            </div>
            <div class="ws-modal-body">
                <div class="ws-modal-form-group">
                    <label for="produceProductSelect">Sản phẩm thành phẩm</label>
                    <select id="produceProductSelect" class="ws-select" style="width:100%"></select>
                </div>
                <div class="ws-modal-form-group">
                    <label for="produceQty">Số lượng cần chế biến</label>
                    <input type="number" id="produceQty" min="1" value="10">
                </div>
                <div class="ws-modal-form-group">
                    <label for="produceNote">Ghi chú</label>
                    <input type="text" id="produceNote" placeholder="Ca sáng, phục vụ suất chiếu...">
                </div>
                <div id="produceRecipePreview" style="padding:10px 12px; background:#f8fafc; border-radius:8px; font-size:0.85rem; color:#475569; max-height:200px; overflow:auto;">
                    <em>Chọn sản phẩm để xem trước nguyên liệu sẽ tiêu hao.</em>
                </div>
            </div>
            <div class="ws-modal-foot">
                <button type="button" class="ws-btn secondary" onclick="closeModal('modalProduce')">Hủy</button>
                <button type="button" class="ws-btn primary" id="btnSubmitProduce">⚙ Chế biến</button>
            </div>
        </div>
    </div>

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
                    tbody.innerHTML = list.map(function(h) {
                        var delta = h.deltaQty > 0 ? ('+' + h.deltaQty) : h.deltaQty;
                        var badgeClass = h.deltaQty > 0 ? 'ws-badge-green' : 'ws-badge-red';
                        return '<tr>' +
                            '<td style="color:var(--ws-text-muted); font-size:0.85rem;">' + (h.createdAt || '—') + '</td>' +
                            '<td><span class="ws-badge ws-status-pill ' + badgeClass + '">' + delta + '</span></td>' +
                            '<td>' + (h.reason || '—') + '</td>' +
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

        // ============================================================
        // TABS
        // ============================================================
        document.querySelectorAll('.ws-tab').forEach(function(btn) {
            btn.addEventListener('click', function() {
                var target = this.getAttribute('data-tab');
                document.querySelectorAll('.ws-tab').forEach(function(b) {
                    b.classList.remove('ws-tab-active');
                    b.style.color = 'var(--ws-text-muted, #6b7280)';
                    b.style.borderBottomColor = 'transparent';
                });
                this.classList.add('ws-tab-active');
                this.style.color = 'var(--ws-accent, #d9363e)';
                this.style.borderBottomColor = 'var(--ws-accent, #d9363e)';
                document.querySelectorAll('.ws-tab-pane').forEach(function(p) {
                    p.style.display = 'none';
                });
                document.getElementById(target).style.display = 'block';
                if (target === 'tab-material') loadMaterials(branchSelect.value);
                if (target === 'tab-recipe') { loadAllRecipes(); loadProductsForRecipe(); }
            });
        });

        // ============================================================
        // TAB 2: KHO NGUYÊN LIỆU
        // ============================================================
        var materialsData = [];
        function loadMaterials(branchId) {
            var tbody = document.getElementById('materialTableBody');
            if (!tbody) return;
            tbody.innerHTML = '<tr><td colspan="8" style="padding:24px; text-align:center;"><div class="ws-list-loading"><div class="ws-spinner"></div><span>Đang tải…</span></div></td></tr>';
            fetch(ctx + '/inventory?type=material&branchId=' + encodeURIComponent(branchId))
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    materialsData = Array.isArray(res) ? res : (res.data || []);
                    renderMaterialTable();
                    populateMaterialSelects();
                })
                .catch(function(err) {
                    tbody.innerHTML = '<tr><td colspan="8" style="padding:24px; text-align:center; color:#dc2626;">Không thể tải dữ liệu nguyên liệu.</td></tr>';
                });
        }
        function renderMaterialTable() {
            var tbody = document.getElementById('materialTableBody');
            var search = (document.getElementById('materialSearchInput').value || '').toLowerCase();
            var cat = document.getElementById('materialCategoryFilter').value;
            var filtered = materialsData.filter(function(m) {
                var matchS = !search || (m.name && m.name.toLowerCase().includes(search)) || (m.code && m.code.toLowerCase().includes(search));
                var matchC = !cat || m.category === cat;
                return matchS && matchC;
            });
            if (filtered.length === 0) {
                tbody.innerHTML = '<tr><td colspan="8" style="padding:32px; text-align:center; color:var(--ws-text-muted);">Chưa có nguyên liệu nào.</td></tr>';
                return;
            }
            tbody.innerHTML = filtered.map(function(m) {
                var qty = m.qty != null ? m.qty : 0;
                var min = m.minStock != null ? m.minStock : 0;
                var low = min > 0 && qty < min;
                var badge = low ? '<span class="ws-badge ws-status-pill ws-badge-orange">Dưới mức tối thiểu</span>'
                               : (qty <= 0 ? '<span class="ws-badge ws-status-pill ws-badge-red">Hết</span>'
                                            : '<span class="ws-badge ws-status-pill ws-badge-green">Còn</span>');
                return '<tr>' +
                    '<td><strong>' + (m.name || '—') + '</strong></td>' +
                    '<td><code style="font-size:0.8rem;">' + (m.code || '—') + '</code></td>' +
                    '<td>' + (m.category || '—') + '</td>' +
                    '<td>' + (m.unit || '—') + '</td>' +
                    '<td><strong style="font-size:1rem;">' + qty + '</strong></td>' +
                    '<td>' + min + '</td>' +
                    '<td style="color:var(--ws-text-muted)">' + (m.supplier || '—') + '</td>' +
                    '<td style="text-align:right;">' +
                        '<button type="button" class="ws-btn ws-btn-sm secondary" onclick="showMaterialHistory(' + m.materialId + ', \'' + (m.name || '').replace(/'/g, "\\'") + '\')">Lịch sử</button> ' +
                        '<button type="button" class="ws-btn ws-btn-sm primary" onclick="quickRestockMaterial(' + m.materialId + ')">Nhập</button> ' +
                        '<button type="button" class="ws-btn ws-btn-sm danger" onclick="quickAdjustMaterial(' + m.materialId + ')">Điều chỉnh</button>' +
                    '</td></tr>';
            }).join('');
        }
        function populateMaterialSelects() {
            var rSel = document.getElementById('restockMaterialSelect');
            var aSel = document.getElementById('adjustMaterialSelect');
            var opts = materialsData.map(function(m) {
                return '<option value="' + m.materialId + '">' + (m.code || ('#' + m.materialId)) + ' — ' + (m.name || '') + ' (tồn: ' + m.qty + ' ' + (m.unit || '') + ')</option>';
            }).join('');
            if (rSel) rSel.innerHTML = '<option value="">Chọn nguyên liệu…</option>' + opts;
            if (aSel) aSel.innerHTML = '<option value="">Chọn nguyên liệu…</option>' + opts;
        }
        document.getElementById('materialSearchInput').addEventListener('input', renderMaterialTable);
        document.getElementById('materialCategoryFilter').addEventListener('change', renderMaterialTable);

        window.quickRestockMaterial = function(materialId) {
            document.getElementById('restockMaterialSelect').value = String(materialId);
            openModal('modalRestockMaterial');
        };
        window.quickAdjustMaterial = function(materialId) {
            document.getElementById('adjustMaterialSelect').value = String(materialId);
            openModal('modalAdjustMaterial');
        };
        window.showMaterialHistory = function(materialId, name) {
            document.getElementById('materialHistoryTitle').textContent = 'Lịch sử biến động: ' + (name || ('NL #' + materialId));
            openModal('modalMaterialHistory');
            var tbody = document.getElementById('materialHistoryTableBody');
            tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding:30px; color:var(--ws-text-muted);">Đang tải…</td></tr>';
            fetch(ctx + '/inventory?type=material&action=history&branchId=' + branchSelect.value + '&materialId=' + materialId)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    var list = Array.isArray(res) ? res : (res.data || []);
                    if (list.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; padding:30px; color:var(--ws-text-muted);">Chưa có lịch sử.</td></tr>';
                        return;
                    }
                    tbody.innerHTML = list.map(function(h) {
                        var d = h.deltaQty;
                        var delta = d > 0 ? ('+' + d) : d;
                        var bc = d > 0 ? 'ws-badge-green' : 'ws-badge-red';
                        var info = h.supplier || h.note || h.poNumber || '—';
                        if (h.costPrice != null) info += ' (' + Number(h.costPrice).toLocaleString('vi-VN') + 'đ)';
                        var when = h.createdAt || '—';
                        return '<tr>' +
                            '<td style="color:var(--ws-text-muted); font-size:0.85rem;">' + when + '</td>' +
                            '<td><span class="ws-badge ws-status-pill ' + bc + '">' + delta + '</span></td>' +
                            '<td>' + (h.reason || '—') + '</td>' +
                            '<td style="font-size:0.85rem;">' + info + '</td>' +
                            '<td><strong>' + (h.actorName || ('ID: ' + h.actorId)) + '</strong></td>' +
                        '</tr>';
                    }).join('');
                });
        };

        document.getElementById('btnOpenRestockMaterial').onclick = function() { openModal('modalRestockMaterial'); };
        document.getElementById('btnOpenAdjustMaterial').onclick = function() { openModal('modalAdjustMaterial'); };

        document.getElementById('btnSubmitRestockMaterial').onclick = function() {
            var bId = branchSelect.value;
            var mId = document.getElementById('restockMaterialSelect').value;
            var qty = parseFloat(document.getElementById('restockMaterialQty').value);
            var reason = (document.getElementById('restockMaterialReason').value || '').trim();
            var cost = document.getElementById('restockMaterialCost').value;
            var supplier = document.getElementById('restockMaterialSupplier').value;
            var po = document.getElementById('restockMaterialPo').value;
            var note = document.getElementById('restockMaterialNote').value;
            if (!mId) { alert('Vui lòng chọn nguyên liệu.'); return; }
            if (!(qty > 0)) { alert('Số lượng phải lớn hơn 0.'); return; }
            if (!reason) reason = 'NHAP_HANG_TU_NCC';
            var body = new URLSearchParams({ type: 'material', branchId: bId, materialId: mId, quantity: qty, reason: reason, supplier: supplier, poNumber: po, note: note });
            if (cost) body.set('costPrice', cost);
            var btn = this; btn.disabled = true; btn.textContent = 'Đang lưu…';
            fetch(ctx + '/inventory', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    btn.disabled = false; btn.textContent = 'Xác nhận nhập';
                    if (res.code && res.code !== 'OK' && res.code !== 200) { alert('Lỗi: ' + (res.message || res.code)); return; }
                    alert('Nhập kho nguyên liệu thành công!');
                    closeModal('modalRestockMaterial');
                    loadMaterials(bId);
                })
                .catch(function(err) { btn.disabled = false; btn.textContent = 'Xác nhận nhập'; alert('Lỗi: ' + err); });
        };

        document.getElementById('btnSubmitAdjustMaterial').onclick = function() {
            var bId = branchSelect.value;
            var mId = document.getElementById('adjustMaterialSelect').value;
            var dir = (document.querySelector('input[name="adjustMatDir"]:checked') || {}).value || 'PLUS';
            var qty = parseFloat(document.getElementById('adjustMaterialQty').value);
            var reason = (document.getElementById('adjustMaterialReason').value || '').trim();
            var note = document.getElementById('adjustMaterialNote').value;
            if (!mId) { alert('Vui lòng chọn nguyên liệu.'); return; }
            if (!(qty > 0)) { alert('Số lượng phải lớn hơn 0.'); return; }
            if (!reason) { alert('Lý do là bắt buộc.'); return; }
            var finalQty = dir === 'MINUS' ? -qty : qty;
            var btn = this; btn.disabled = true; btn.textContent = 'Đang lưu…';
            fetch(ctx + '/inventory', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({ action: 'adjust', type: 'material', branchId: bId, materialId: mId, quantity: finalQty, reason: reason, note: note }) })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    btn.disabled = false; btn.textContent = 'Xác nhận';
                    if (res.code && res.code !== 'OK' && res.code !== 200) { alert('Lỗi: ' + (res.message || res.code)); return; }
                    alert('Điều chỉnh nguyên liệu thành công!');
                    closeModal('modalAdjustMaterial');
                    loadMaterials(bId);
                })
                .catch(function(err) { btn.disabled = false; btn.textContent = 'Xác nhận'; alert('Lỗi: ' + err); });
        };

        // ============================================================
        // TAB 3: CÔNG THỨC CHẾ BIẾN & SẢN XUẤT
        // ============================================================
        var allRecipesData = [];
        var recipeProductList = [];
        function loadAllRecipes() {
            return fetch(ctx + '/inventory?action=recipes')
                .then(function(r) { return r.json(); })
                .then(function(res) { allRecipesData = Array.isArray(res) ? res : (res.data || []); renderRecipeTable(); });
        }
        function loadProductsForRecipe() {
            if (recipeProductList.length > 0) return Promise.resolve();
            return fetch(ctx + '/concession/products')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    recipeProductList = Array.isArray(res) ? res : (res.data || []);
                    var sels = [document.getElementById('recipeProductSelect'), document.getElementById('produceProductSelect')];
                    sels.forEach(function(sel) {
                        if (!sel) return;
                        sel.innerHTML = '<option value="">Chọn sản phẩm…</option>' +
                            recipeProductList.map(function(p) { return '<option value="' + p.id + '">' + (p.name || ('SP #' + p.id)) + ' (' + (p.type || '') + ')</option>'; }).join('');
                    });
                })
                .catch(function() { /* fallback: dùng stockData nếu có */ });
        }
        function renderRecipeTable() {
            var tbody = document.getElementById('recipeTableBody');
            var pid = document.getElementById('recipeProductSelect').value;
            var search = (document.getElementById('recipeSearch').value || '').toLowerCase();
            if (!pid) {
                tbody.innerHTML = '<tr><td colspan="7" style="padding:32px; text-align:center; color:var(--ws-text-muted);">Chọn sản phẩm để xem / sửa công thức.</td></tr>';
                return;
            }
            var rows = allRecipesData.filter(function(r) { return String(r.productId) === String(pid); })
                .filter(function(r) { return !search || (r.materialName && r.materialName.toLowerCase().includes(search)) || (r.materialCode && r.materialCode.toLowerCase().includes(search)); });
            if (rows.length === 0) {
                tbody.innerHTML = '<tr><td colspan="7" style="padding:32px; text-align:center; color:var(--ws-text-muted);">Sản phẩm này chưa có công thức. Nhấn <strong>+ Thêm nguyên liệu</strong> để bắt đầu.</td></tr>';
                return;
            }
            tbody.innerHTML = rows.map(function(r, idx) {
                return '<tr data-recipe-rid="' + r.id + '">' +
                    '<td><code style="font-size:0.8rem;">' + (r.materialCode || '—') + '</code></td>' +
                    '<td><strong>' + (r.materialName || '—') + '</strong></td>' +
                    '<td><input type="number" class="ws-input recipe-dosage" min="0.001" step="0.001" value="' + r.quantityRequired + '" style="width:120px; padding:6px 8px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"></td>' +
                    '<td>' + (r.unit || '') + '</td>' +
                    '<td><input type="number" class="ws-input recipe-loss" min="0" max="0.95" step="0.01" value="' + r.lossRate + '" style="width:80px; padding:6px 8px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"></td>' +
                    '<td><input type="text" class="ws-input recipe-note" value="' + (r.note || '').replace(/"/g, '&quot;') + '" placeholder="—" style="width:180px; padding:6px 8px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"></td>' +
                    '<td style="text-align:right;"><button type="button" class="ws-btn ws-btn-sm danger" onclick="removeRecipeRow(this)">Xóa</button></td>' +
                '</tr>';
            }).join('');
        }
        window.removeRecipeRow = function(btn) {
            var tr = btn.closest('tr');
            var rid = tr.getAttribute('data-recipe-rid');
            // Xóa khỏi allRecipesData để khi lưu không gửi lại
            allRecipesData = allRecipesData.filter(function(r) { return String(r.id) !== String(rid); });
            tr.remove();
        };
        document.getElementById('recipeProductSelect').addEventListener('change', renderRecipeTable);
        document.getElementById('recipeSearch').addEventListener('input', renderRecipeTable);

        document.getElementById('btnAddRecipeItem').onclick = function() {
            var pid = document.getElementById('recipeProductSelect').value;
            if (!pid) { alert('Vui lòng chọn sản phẩm trước.'); return; }
            // Thêm 1 dòng trống với danh sách nguyên liệu làm option
            var tbody = document.getElementById('recipeTableBody');
            var opts = materialsData.map(function(m) { return '<option value="' + m.materialId + '">' + (m.code || '') + ' — ' + (m.name || '') + '</option>'; }).join('');
            var tr = document.createElement('tr');
            tr.setAttribute('data-recipe-new', '1');
            tr.innerHTML = '<td colspan="2"><select class="ws-select recipe-mat" style="width:100%; padding:6px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"><option value="">-- chọn NL --</option>' + opts + '</select></td>' +
                '<td><input type="number" class="ws-input recipe-dosage" min="0.001" step="0.001" value="1" style="width:120px; padding:6px 8px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"></td>' +
                '<td><input type="text" class="ws-input recipe-unit" value="" placeholder="đơn vị" style="width:80px; padding:6px 8px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"></td>' +
                '<td><input type="number" class="ws-input recipe-loss" min="0" max="0.95" step="0.01" value="0" style="width:80px; padding:6px 8px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"></td>' +
                '<td><input type="text" class="ws-input recipe-note" placeholder="—" style="width:180px; padding:6px 8px; border:1px solid var(--ws-line-strong, #d8dce3); border-radius:6px;"></td>' +
                '<td style="text-align:right;"><button type="button" class="ws-btn ws-btn-sm danger" onclick="this.closest(\'tr\').remove()">Xóa</button></td>';
            // Nếu tbody chỉ có placeholder row thì xóa
            if (tbody.children.length === 1 && tbody.firstElementChild.children[0].getAttribute('colspan') === '7') tbody.innerHTML = '';
            tbody.appendChild(tr);
        };

        document.getElementById('btnSaveRecipe').onclick = function() {
            var pid = document.getElementById('recipeProductSelect').value;
            if (!pid) { alert('Vui lòng chọn sản phẩm.'); return; }
            var rows = document.querySelectorAll('#recipeTableBody tr');
            var items = [];
            rows.forEach(function(tr) {
                if (tr.getAttribute('data-recipe-new') === '1') {
                    var sel = tr.querySelector('.recipe-mat');
                    var mId = sel ? sel.value : '';
                    if (!mId) return;
                } else {
                    var rid = tr.getAttribute('data-recipe-rid');
                    var rec = allRecipesData.find(function(r) { return String(r.id) === String(rid); });
                    if (!rec) return;
                    var mId = rec.materialId;
                }
                items.push({
                    materialId: mId,
                    dosage: parseFloat(tr.querySelector('.recipe-dosage').value) || 0,
                    lossRate: parseFloat(tr.querySelector('.recipe-loss').value) || 0,
                    note: tr.querySelector('.recipe-note').value || '',
                    unit: (tr.querySelector('.recipe-unit') && tr.querySelector('.recipe-unit').value) || ''
                });
            });
            var btn = this; btn.disabled = true; btn.textContent = 'Đang lưu…';
            fetch(ctx + '/inventory', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({ action: 'save_recipe', productId: pid, items: JSON.stringify(items) }) })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    btn.disabled = false; btn.textContent = '💾 Lưu công thức';
                    if (res.code && res.code !== 'OK' && res.code !== 200) { alert('Lỗi: ' + (res.message || res.code)); return; }
                    alert((res.message || 'Đã lưu công thức!'));
                    loadAllRecipes();
                })
                .catch(function(err) { btn.disabled = false; btn.textContent = '💾 Lưu công thức'; alert('Lỗi: ' + err); });
        };

        // Preview nguyên liệu cần dùng khi chọn sản phẩm chế biến
        document.getElementById('produceProductSelect').addEventListener('change', updateProducePreview);
        document.getElementById('produceQty').addEventListener('input', updateProducePreview);
        function updateProducePreview() {
            var pid = document.getElementById('produceProductSelect').value;
            var qty = parseFloat(document.getElementById('produceQty').value) || 0;
            var box = document.getElementById('produceRecipePreview');
            if (!pid) { box.innerHTML = '<em>Chọn sản phẩm để xem trước nguyên liệu sẽ tiêu hao.</em>'; return; }
            var rows = allRecipesData.filter(function(r) { return String(r.productId) === String(pid); });
            if (rows.length === 0) { box.innerHTML = '<em style="color:#dc2626;">Sản phẩm chưa có công thức — hãy tạo công thức trước khi chế biến.</em>'; return; }
            box.innerHTML = '<strong>Sẽ tiêu hao:</strong><ul style="margin:6px 0 0 16px; padding:0;">' +
                rows.map(function(r) {
                    return '<li>' + (r.materialName || r.materialCode) + ': <strong>' + (r.quantityRequired * qty).toFixed(3) + '</strong> ' + (r.unit || '') + '</li>';
                }).join('') + '</ul>';
        }

        document.getElementById('btnOpenProduce').onclick = function() {
            if (allRecipesData.length === 0) loadAllRecipes();
            if (recipeProductList.length === 0) loadProductsForRecipe();
            updateProducePreview();
            openModal('modalProduce');
        };

        document.getElementById('btnSubmitProduce').onclick = function() {
            var bId = branchSelect.value;
            var pid = document.getElementById('produceProductSelect').value;
            var qty = parseInt(document.getElementById('produceQty').value, 10);
            var note = document.getElementById('produceNote').value;
            if (!pid) { alert('Vui lòng chọn sản phẩm.'); return; }
            if (!(qty > 0)) { alert('Số lượng phải lớn hơn 0.'); return; }
            var btn = this; btn.disabled = true; btn.textContent = 'Đang chế biến…';
            fetch(ctx + '/inventory', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({ action: 'produce', branchId: bId, productId: pid, productQty: qty, note: note }) })
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    btn.disabled = false; btn.textContent = '⚙ Chế biến';
                    if (res.code && res.code !== 'OK' && res.code !== 200) { alert('Lỗi: ' + (res.message || res.code)); return; }
                    alert((res.message || 'Chế biến thành công!'));
                    closeModal('modalProduce');
                    loadStock(bId); loadMaterials(bId);
                })
                .catch(function(err) { btn.disabled = false; btn.textContent = '⚙ Chế biến'; alert('Lỗi: ' + err); });
        };

        // Load materials & recipes khi đổi chi nhánh
        var _origBranchHandler = branchSelect.onchange;
        branchSelect.addEventListener('change', function() {
            if (document.getElementById('tab-material').style.display !== 'none') loadMaterials(this.value);
        });

        // Khi bật tab recipe lần đầu, tải materials để dùng cho "thêm nguyên liệu"
        document.querySelector('[data-tab="tab-recipe"]').addEventListener('click', function() {
            if (materialsData.length === 0) loadMaterials(branchSelect.value);
        });

        // Khởi động
        loadBranches();
    })();
    </script>
</body>
</html>
