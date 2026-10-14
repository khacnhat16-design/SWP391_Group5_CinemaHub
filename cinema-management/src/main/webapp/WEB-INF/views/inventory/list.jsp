<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="ctx" value="${pageContext.request.contextPath}" scope="request"/>
<c:set var="currentRole" value="${empty sessionScope.role ? 'GUEST' : sessionScope.role}"/>
<c:set var="activeNav" value="inventory" scope="request"/>
<c:set var="pageTitle" value="Quản lý tồn kho bắp nước" scope="request"/>
<c:set var="pageEyebrow" value="GIAI ĐOẠN 1 — MÀN 1 (M-P1)" scope="request"/>

<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <meta name="ctx" content="${ctx}">
    <meta name="user-role" content="${currentRole}">
    <title>Quản lý tồn kho bắp nước | CinemaHub</title>
    <link rel="stylesheet" href="${ctx}/assets/css/cinema.css">
    <link rel="stylesheet" href="${ctx}/assets/css/workspace.css">
    <link rel="stylesheet" href="${ctx}/assets/css/inventory.css">
</head>
<body class="workspace-page">
    <%@ include file="/WEB-INF/views/layout/workspace-sidebar.jsp" %>
    <main class="ws-main" id="wsMain">
        <%@ include file="/WEB-INF/views/layout/workspace-header.jsp" %>
        
        <div class="ws-main-inner" style="padding: 24px 32px;">
            <!-- THẺ THỐNG KÊ NHANH -->
            <div class="inv-stats-grid">
                <div class="inv-stat-card">
                    <h6>Tổng số món</h6>
                    <div class="inv-stat-val" id="statTotal">0</div>
                </div>
                <div class="inv-stat-card" style="border-left: 4px solid #16a34a;">
                    <h6 style="color: #16a34a;">Còn hàng</h6>
                    <div class="inv-stat-val" id="statInStock" style="color: #16a34a;">0</div>
                </div>
                <div class="inv-stat-card" style="border-left: 4px solid #ea580c;">
                    <h6 style="color: #ea580c;">Sắp hết hàng</h6>
                    <div class="inv-stat-val" id="statLowStock" style="color: #ea580c;">0</div>
                </div>
                <div class="inv-stat-card" style="border-left: 4px solid #dc2626;">
                    <h6 style="color: #dc2626;">Hết hàng</h6>
                    <div class="inv-stat-val" id="statOutOfStock" style="color: #dc2626;">0</div>
                </div>
            </div>

            <!-- CARD BẢNG TỒN KHO VÀ THANH CÔNG CỤ -->
            <div class="inv-card">
                <div class="inv-toolbar">
                    <div class="inv-filters">
                        <!-- Chọn chi nhánh -->
                        <label style="font-weight: 600; font-size: 0.88rem; color: #475569;">Chi nhánh:</label>
                        <select class="inv-select" id="branchSelect" style="font-weight: 600;">
                            <option value="">Đang tải chi nhánh…</option>
                        </select>

                        <!-- Tìm kiếm -->
                        <input type="search" class="inv-search-input" id="searchInput" placeholder="Tìm tên món bắp nước…">

                        <!-- Lọc loại món -->
                        <select class="inv-select" id="typeFilter">
                            <option value="">Tất cả loại món</option>
                            <option value="POPCORN">Bắp rang (POPCORN)</option>
                            <option value="DRINK">Nước ngọt (DRINK)</option>
                            <option value="COMBO">Combo bắp nước</option>
                            <option value="OTHER">Món khác</option>
                        </select>

                        <!-- Lọc trạng thái -->
                        <select class="inv-select" id="statusFilter">
                            <option value="">Tất cả trạng thái</option>
                            <option value="IN_STOCK">Còn hàng</option>
                            <option value="LOW_STOCK">Sắp hết (&lt; 10)</option>
                            <option value="OUT_OF_STOCK">Hết hàng (0)</option>
                        </select>
                    </div>

                    <div>
                        <button type="button" class="ws-btn secondary" id="btnRefresh" style="padding: 8px 14px; font-weight: 600;">
                            Làm mới
                        </button>
                    </div>
                </div>

                <!-- BẢNG HIỂN THỊ DANH SÁCH -->
                <div style="overflow-x: auto;">
                    <table class="inv-table">
                        <thead>
                            <tr>
                                <th style="width: 60px; text-align: center;">#</th>
                                <th>Tên sản phẩm</th>
                                <th style="width: 140px;">Phân loại</th>
                                <th style="width: 100px;">ĐVT</th>
                                <th style="width: 130px; text-align: right;">Đơn giá</th>
                                <th style="width: 130px; text-align: right;">Tồn kho</th>
                                <th style="width: 140px; text-align: center;">Trạng thái</th>
                            </tr>
                        </thead>
                        <tbody id="inventoryTableBody">
                            <tr>
                                <td colspan="7" style="text-align: center; padding: 40px; color: #64748b;">
                                    Đang tải dữ liệu tồn kho…
                                </td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    </main>

    <script>
    (function () {
        const ctx = '${ctx}';
        let allItems = [];

        const branchSelect = document.getElementById('branchSelect');
        const searchInput = document.getElementById('searchInput');
        const typeFilter = document.getElementById('typeFilter');
        const statusFilter = document.getElementById('statusFilter');
        const tableBody = document.getElementById('inventoryTableBody');
        const btnRefresh = document.getElementById('btnRefresh');

        const statTotal = document.getElementById('statTotal');
        const statInStock = document.getElementById('statInStock');
        const statLowStock = document.getElementById('statLowStock');
        const statOutOfStock = document.getElementById('statOutOfStock');

        // 1. Tải danh sách chi nhánh
        function loadBranches() {
            fetch(ctx + '/branch')
                .then(res => res.json())
                .then(data => {
                    const list = Array.isArray(data) ? data : (data && data.data ? data.data : []);
                    branchSelect.replaceChildren();
                    if (!list.length) {
                        branchSelect.appendChild(new Option('Chi nhánh mặc định (#1)', '1'));
                    } else {
                        list.forEach(b => {
                            const opt = new Option(b.name, b.id);
                            branchSelect.appendChild(opt);
                        });
                    }
                    loadInventory();
                })
                .catch(() => {
                    branchSelect.replaceChildren(new Option('Chi nhánh #1', '1'));
                    loadInventory();
                });
        }

        // 2. Tải tồn kho theo chi nhánh
        function loadInventory() {
            const bId = branchSelect.value || 1;
            tableBody.replaceChildren();
            const trLoading = document.createElement('tr');
            trLoading.innerHTML = '<td colspan="7" style="text-align: center; padding: 30px; color: #64748b;">Đang tải dữ liệu…</td>';
            tableBody.appendChild(trLoading);

            fetch(ctx + '/inventory?branchId=' + bId)
                .then(res => res.json())
                .then(data => {
                    allItems = Array.isArray(data) ? data : (data && data.data ? data.data : []);
                    renderTable();
                })
                .catch(err => {
                    tableBody.replaceChildren();
                    const trErr = document.createElement('tr');
                    trErr.innerHTML = '<td colspan="7" style="text-align: center; padding: 30px; color: #dc2626;">Không thể tải tồn kho: ' + (err.message || 'Lỗi kết nối') + '</td>';
                    tableBody.appendChild(trErr);
                });
        }

        // 3. Render bảng & Cập nhật thống kê
        function renderTable() {
            const keyword = (searchInput.value || '').trim().toLowerCase();
            const selectedType = typeFilter.value;
            const selectedStatus = statusFilter.value;

            // Thống kê tổng
            let countIn = 0, countLow = 0, countOut = 0;
            allItems.forEach(item => {
                const q = item.qty != null ? item.qty : 0;
                if (q <= 0) countOut++;
                else if (q < 10) countLow++;
                else countIn++;
            });
            statTotal.textContent = allItems.length;
            statInStock.textContent = countIn;
            statLowStock.textContent = countLow;
            statOutOfStock.textContent = countOut;

            // Lọc dữ liệu
            const filtered = allItems.filter(item => {
                const q = item.qty != null ? item.qty : 0;
                const name = (item.name || '').toLowerCase();
                if (keyword && !name.includes(keyword)) return false;
                if (selectedType && item.type !== selectedType) return false;
                if (selectedStatus === 'IN_STOCK' && q < 10) return false;
                if (selectedStatus === 'LOW_STOCK' && (q <= 0 || q >= 10)) return false;
                if (selectedStatus === 'OUT_OF_STOCK' && q > 0) return false;
                return true;
            });

            tableBody.replaceChildren();

            if (!filtered.length) {
                const trEmpty = document.createElement('tr');
                trEmpty.innerHTML = '<td colspan="7" style="text-align: center; padding: 36px; color: #64748b;">Không tìm thấy sản phẩm bắp nước nào phù hợp.</td>';
                tableBody.appendChild(trEmpty);
                return;
            }

            filtered.forEach((item, index) => {
                const q = item.qty != null ? item.qty : 0;
                let badgeClass = 'inv-badge-green';
                let badgeText = 'Còn hàng';
                if (q <= 0) {
                    badgeClass = 'inv-badge-red';
                    badgeText = 'Hết hàng';
                } else if (q < 10) {
                    badgeClass = 'inv-badge-orange';
                    badgeText = 'Sắp hết';
                }

                let typeBadge = item.type || 'Món lẻ';
                if (typeBadge === 'POPCORN') typeBadge = 'Bắp';
                if (typeBadge === 'DRINK') typeBadge = 'Nước';
                if (typeBadge === 'COMBO') typeBadge = 'Combo';

                const formattedPrice = (item.price != null ? item.price : 0).toLocaleString('vi-VN') + ' đ';

                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td style="text-align: center; color: #94a3b8; font-size: 0.85rem;">\${index + 1}</td>
                    <td style="font-weight: 700; color: #0f172a;">\${escapeHtml(item.name || '—')}</td>
                    <td><span class="inv-badge inv-badge-gray">\${escapeHtml(typeBadge)}</span></td>
                    <td style="color: #64748b;">\${escapeHtml(item.unit || 'phần')}</td>
                    <td style="text-align: right; color: #334155;">\${formattedPrice}</td>
                    <td style="text-align: right; font-weight: 800; font-size: 1rem; color: \${q <= 0 ? '#dc2626' : (q < 10 ? '#ea580c' : '#0f172a')};">\${q}</td>
                    <td style="text-align: center;"><span class="inv-badge \${badgeClass}">\${badgeText}</span></td>
                `;
                tableBody.appendChild(tr);
            });
        }

        function escapeHtml(str) {
            return String(str).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[m]);
        }

        branchSelect.addEventListener('change', loadInventory);
        searchInput.addEventListener('input', renderTable);
        typeFilter.addEventListener('change', renderTable);
        statusFilter.addEventListener('change', renderTable);
        btnRefresh.addEventListener('click', loadInventory);

        loadBranches();
    })();
    </script>
</body>
</html>
