/**
 * console.js — Renderer cho khu vuc van hanh (/console).
 * Chi goi REST API co san va dung DOM bang CinemaHub helpers (app.js).
 * Module chon theo ?module= khop link trong WEB-INF/views/layout/sidebar.jsp.
 * SU DUNG .then()/.catch() CHAIN — KHONG DUNG async/await.
 */
(function () {
  "use strict";

  var hub = window.CinemaHub;
  if (!hub) return;

  var viewRoot = document.getElementById("viewRoot");
  var topbarActions = document.getElementById("topbarActions");
  if (!viewRoot) return;

  var params = new URLSearchParams(window.location.search);
  var role =
    (document.querySelector('meta[name="user-role"]') || {}).content || "GUEST";
  var isWorkspace = document.body.classList.contains("workspace-page");
  var currentModule = params.get("module") || "dashboard";

  // ---------- DOM shorthand ----------
  var el = hub.el;

  function card(title, sub) {
    var box = el("section", { class: "app-card" });
    if (title) box.appendChild(el("h2", {}, [title]));
    if (sub) box.appendChild(el("p", { class: "sub" }, [sub]));
    return box;
  }

  function loading() {
    viewRoot.setAttribute("aria-busy", "true");
    viewRoot.replaceChildren(
      el("div", { class: "loading-state", role: "status" }, [
        el("span", { class: "spinner", "aria-hidden": "true" }),
        el("span", {}, ["Dang tai du lieu..."]),
      ]),
    );
  }

  function emptyState(title, message, actionLabel, actionHref) {
    var box = el("div", { class: "empty-state" }, [
      el("h3", {}, [title]),
      el("p", {}, [message]),
    ]);
    if (actionLabel) {
      box.appendChild(
        el("a", { class: "btn secondary", href: actionHref || "#" }, [
          actionLabel,
        ]),
      );
    }
    viewRoot.replaceChildren(box);
  }

  function errorState(err) {
    var forbidden = err.status === 403 || err.code === "FORBIDDEN";
    emptyState(
      forbidden ? "Khong co quyen truy cap" : "Khong tai duoc du lieu",
      err.message || "Da xay ra loi. Vui long thu lai.",
      "Thu lai",
    );
    var link = viewRoot.querySelector("a");
    if (link) {
      link.addEventListener("click", function (e) {
        e.preventDefault();
        route(currentModule);
      });
    }
  }

  function kpiGrid(items) {
    var grid = el("div", { class: "kpi-grid" });
    items.forEach(function (item) {
      var box = el("div", { class: "kpi" }, [
        el("span", {}, [item.label]),
        el("strong", {}, [item.value]),
      ]);
      if (item.hint) box.appendChild(el("small", {}, [item.hint]));
      grid.appendChild(box);
    });
    return grid;
  }

  // ---------- shared helpers ----------
  function escapeHtml(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return {
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;",
      }[c];
    });
  }

  function fmtMoneyVN(value) {
    if (value == null || isNaN(value)) return "0 đ";
    try {
      return hub.fmtMoney(value);
    } catch (e) {
      return Number(value).toLocaleString("vi-VN") + " đ";
    }
  }

  function statusBadge(label) {
    var map = {
      PAID: "green",
      CONFIRMED: "green",
      USED: "purple",
      SELLING: "blue",
      OPEN: "blue",
      SOLD_OUT: "red",
      ALMOST_FULL: "orange",
      PENDING: "orange",
      CANCELLED: "red",
      ENDED: "gray",
      INACTIVE: "red",
      ACTIVE: "green",
      EXPIRED: "gray",
      HOLD: "orange",
      FAILED: "red",
    };
    var tone = map[label] || "gray";
    return (
      '<span class="ws-badge ws-status-pill ws-badge-' +
      tone +
      '">' +
      escapeHtml(String(label || "")) +
      "</span>"
    );
  }

  function debounce(fn, ms) {
    var t;
    return function () {
      clearTimeout(t);
      t = setTimeout(fn, ms);
    };
  }

  function emptyModule(title, message) {
    emptyState(
      title,
      message || "Muc nay hien chua duoc kich hoat cho vai tro cua ban.",
    );
  }

  function placeholderModule(title, desc) {
    emptyState(
      title,
      desc || "Chuc nang nay hien chua co du lieu de hien thi.",
    );
  }

  // ---------- MODULES DISPATCH ----------
  function dispatchBusiness(name) {
    // Ưu tiên CinemaHubFormPages (pattern mới) trước — cho phép các module
    // đăng ký { list, view, create, edit } qua router.register giống admin.
    var formPages = window.CinemaHubFormPages || {};
    if (formPages[name] && typeof formPages[name].list === "function") {
      return formPages[name].list;
    }
    var pages = window.CinemaHubPages || {};
    var handler = pages[name];
    if (typeof handler === "function") return handler;
    return null;
  }

  // ============ DASHBOARD ============
  function moduleDashboard() {
    loading();
    if (!isWorkspace) {
      // Customer dashboard must not call the internal reporting API.
      // Render customer-safe account data and links instead.
      hub
        .api("/api/profile")
        .then(function (data) {
          var user = (data && data.user) || {};
          var box = card(
            "Bảng điều khiển",
            user.fullName
              ? "Xin chào, " + user.fullName + "!"
              : "Tổng quan tài khoản của bạn.",
          );
          box.appendChild(
            kpiGrid([
              {
                label: "Hạng thành viên",
                value: String(((data || {}).profile || {}).tier || "STANDARD"),
              },
              {
                label: "Điểm tích lũy",
                value: String(((data || {}).profile || {}).points || 0),
              },
            ]),
          );
          var actions = el("div", { class: "ws-dashboard-actions" }, [
            el(
              "a",
              {
                class: "ws-btn primary",
                href: hub.ctx + "/console?module=discovery",
              },
              ["Đặt vé xem phim"],
            ),
            el(
              "a",
              {
                class: "ws-btn secondary",
                href: hub.ctx + "/console?module=my-tickets",
              },
              ["Vé của tôi"],
            ),
            el(
              "a",
              {
                class: "ws-btn secondary",
                href: hub.ctx + "/console?module=profile",
              },
              ["Hồ sơ"],
            ),
          ]);
          box.appendChild(actions);
          viewRoot.replaceChildren(box);
        })
        .catch(errorState);
      return;
    }
    var dashQs = (typeof window.__currentDashboardBranchId !== 'undefined' && window.__currentDashboardBranchId)
      ? "?branchId=" + encodeURIComponent(window.__currentDashboardBranchId) : "";
    hub.api("/api/dashboard" + dashQs).then(renderWorkspaceDashboard).catch(errorState);
  }

  /**
   * Rail "Phim đang chiếu" tái sử dụng — gọi /movie, lọc status=PUBLISHED và
   * còn hiệu lực (release_date <= today <= end_date), sort release_date DESC
   * rồi render card grid theo CSS .movie-grid / .movie-card.
   */
  function appendNowShowingRail(mount) {
    if (!mount || !hub || !hub.api) return;
    var section = el("section", { class: "ws-dashboard-section" }, [
      el("div", { class: "ws-dashboard-section-head" }, [
        el("div", {}, [
          el("span", { class: "eyebrow" }, ["PHIM ĐANG CHIẾU"]),
          el("h2", {}, ["Phim mới nhất đang được chiếu"]),
        ]),
        el(
          "a",
          {
            class: "btn ghost small",
            href: hub.ctx + "/console?module=discovery",
          },
          ["Xem tất cả"],
        ),
      ]),
      el("div", { class: "movie-grid", "aria-busy": "true" }, [
        el("div", { class: "empty-state" }, [
          el("p", {}, ["Đang tải danh sách phim…"]),
        ]),
      ]),
    ]);
    mount.appendChild(section);
    var grid = section.querySelector(".movie-grid");
    hub
      .api("/movie")
      .then(function (data) {
        var all = Array.isArray(data)
          ? data
          : data && Array.isArray(data.items)
            ? data.items
            : [];
        var today = new Date();
        today.setHours(0, 0, 0, 0);
        var list = (all || [])
          .filter(function (m) {
            if (!m) return false;
            if (m.status && m.status !== "PUBLISHED") return false;
            if (m.endDate) {
              var end = new Date(m.endDate);
              if (!isNaN(end.getTime()) && end < today) return false;
            }
            if (m.releaseDate) {
              var rel = new Date(m.releaseDate);
              if (!isNaN(rel.getTime()) && rel > today) return false;
            }
            return true;
          })
          .sort(function (a, b) {
            var ad = a.releaseDate ? new Date(a.releaseDate).getTime() : 0;
            var bd = b.releaseDate ? new Date(b.releaseDate).getTime() : 0;
            if (bd !== ad) return bd - ad;
            return (b.id || 0) - (a.id || 0);
          });
        grid.removeAttribute("aria-busy");
        if (!list.length) {
          grid.replaceChildren(
            el("div", { class: "empty-state" }, [
              el("h3", {}, ["Chưa có phim đang chiếu"]),
              el("p", {}, ["Hãy quay lại sau khi phim mới được công chiếu."]),
            ]),
          );
          return;
        }
        grid.replaceChildren.apply(
          grid,
          list.map(function (m) {
            return renderDashboardMovieCard(m);
          }),
        );
      })
      .catch(function (err) {
        grid.removeAttribute("aria-busy");
        grid.replaceChildren(
          el("div", { class: "empty-state" }, [
            el("p", {}, [
              "Không thể tải danh sách phim: " +
                (err && err.message ? err.message : "Lỗi máy chủ."),
            ]),
          ]),
        );
      });
  }

  function renderDashboardMovieCard(m) {
    var title = m.title || "Phim #" + m.id;
    var poster = m.posterUrl;
    var rating =
      m.averageRating != null ? Number(m.averageRating).toFixed(1) : null;
    var detailHref =
      hub.ctx + "/console?module=discovery&movieId=" + encodeURIComponent(m.id);
    var meta = [];
    if (m.genre) meta.push(m.genre);
    if (m.durationMin) meta.push(m.durationMin + " phút");
    if (m.rating) meta.push("Phân loại: " + m.rating);
    var posterNode = poster
      ? el("img", {
          src: poster,
          alt: title,
          loading: "lazy",
          referrerpolicy: "no-referrer",
          "data-poster-fallback": "poster-placeholder",
        })
      : el("div", { class: "poster-placeholder" }, ["NO POSTER"]);
    return el("article", { class: "movie-card" }, [
      el(
        "a",
        { class: "poster", href: detailHref },
        [
          posterNode,
          rating ? el("span", { class: "rating" }, ["★ " + rating]) : null,
          el("span", { class: "quick-book" }, ["Đặt vé"]),
        ].filter(Boolean),
      ),
      el("div", { class: "movie-info" }, [
        el("h3", { title: title }, [title]),
        el("p", {}, [meta.join(" · ") || "Đang cập nhật"]),
      ]),
    ]);
  }

  function renderWorkspaceDashboard(data) {
    viewRoot.replaceChildren();

    var greeting =
      role === "ADMIN"
        ? "Tổng quan toàn chuỗi rạp"
        : role === "BRANCH_MANAGER"
          ? "Tổng quan chi nhánh của bạn"
          : role === "BRANCH_STAFF"
            ? "Ca làm việc và doanh số hôm nay"
            : "Tổng quan nhanh theo vai trò của bạn.";

    var heading = el("div", { class: "ws-page-heading" }, [
      el("span", { class: "eyebrow" }, ["Workspace"]),
      el("h1", {}, ["Chào mừng đến với CinemaHub"]),
      el("p", {}, [greeting]),
    ]);

    if (role === "ADMIN") {
      var currentBranchId =
        typeof window.__currentDashboardBranchId !== "undefined" &&
        window.__currentDashboardBranchId
          ? String(window.__currentDashboardBranchId)
          : "";

      var dashBranchSel = el("select", { class: "ws-select", style: "margin-left:8px;" }, [
        el("option", { value: "", selected: !currentBranchId }, [
          "Tất cả chi nhánh (Toàn chuỗi)",
        ]),
      ]);

      hub.api("/branch").then(function (branches) {
        var branchList = Array.isArray(branches) ? branches : [];
        branchList.forEach(function (b) {
          var isSel = currentBranchId === String(b.id);
          var opt = el("option", { value: String(b.id), selected: isSel }, [
            b.name || "#" + b.id,
          ]);
          dashBranchSel.appendChild(opt);
        });
        if (currentBranchId) {
          dashBranchSel.value = currentBranchId;
        }
      }).catch(function () {});

      dashBranchSel.addEventListener("change", function () {
        var val = this.value || "";
        window.__currentDashboardBranchId = val;
        var qs = val ? "?branchId=" + encodeURIComponent(val) : "";
        hub.api("/api/dashboard" + qs).then(renderWorkspaceDashboard).catch(errorState);
      });

      var branchFilterWrap = el(
        "div",
        {
          class: "ws-dashboard-filter",
          style: "margin-top:12px;display:flex;align-items:center;",
        },
        [
          el("label", { style: "font-weight:500;font-size:0.9rem;color:#4b5563;" }, [
            "Lọc theo Chi nhánh: ",
          ]),
          dashBranchSel,
        ],
      );
      heading.appendChild(branchFilterWrap);
    }

    viewRoot.appendChild(heading);

    // ----- KPI Cards (4-6 thẻ ban đầu) -----
    var kpiSpec = [
      {
        label: "Tổng doanh thu",
        value: hub.fmtMoney(data.totalRevenue || 0),
        tone: "primary",
      },
      { label: "Số vé đã bán", value: String(data.ticketsSold || 0) },
      {
        label: "Số vé bị hủy",
        value: String(data.ticketsCancelled || 0),
        tone: "danger",
      },
      { label: "Tổng booking", value: String(data.totalBookings || 0) },
      { label: "Chi nhánh hoạt động", value: String(data.activeBranches || 0) },
    ];
    // Admin/Manager thấy thêm KPI từ booking breakdown
    if (data.bookingBreakdown) {
      var b = data.bookingBreakdown;
      kpiSpec.push({ label: "Khách online", value: String(b.online || 0) });
      kpiSpec.push({ label: "Khách POS", value: String(b.pos || 0) });
      kpiSpec.push({ label: "Đặt trước", value: String(b.advance || 0) });
      kpiSpec.push({ label: "Đặt tại quầy", value: String(b.walkIn || 0) });
    }

    var kpiGrid = el("div", { class: "ws-kpi-grid ws-dashboard-kpis" });
    kpiSpec.forEach(function (k) {
      var cls = "ws-kpi";
      if (k.tone === "primary") cls += " ws-kpi-primary";
      else if (k.tone === "danger") cls += " ws-kpi-danger";
      kpiGrid.appendChild(
        el("div", { class: cls }, [
          el("div", { class: "ws-kpi-head" }, [
            el("span", { class: "ws-kpi-label" }, [k.label]),
          ]),
          el("div", { class: "ws-kpi-value" }, [k.value]),
        ]),
      );
    });
    viewRoot.appendChild(kpiGrid);

    // ----- Hàng 2: Monthly revenue (SVG bar chart) + Booking breakdown -----
    var row2 = el("div", { class: "ws-dashboard-row" });

    // Monthly revenue chart (simple SVG bars)
    var monthly = data.monthlyRevenue || [];
    var chartCard = el("div", { class: "ws-card ws-dashboard-chart-card" });
    chartCard.appendChild(
      el("h3", { class: "ws-dashboard-card-title" }, [
        "Doanh thu theo tháng (kỳ đã chọn)",
      ]),
    );
    if (!monthly.length) {
      chartCard.appendChild(
        el("div", { class: "ws-dashboard-empty" }, [
          "Chưa có dữ liệu doanh thu.",
        ]),
      );
    } else {
      chartCard.appendChild(renderMonthlyRevenueChart(monthly));
      // Bảng nhỏ bên dưới chart
      var tbl = el("table", { class: "ws-table ws-dashboard-mini-table" });
      tbl.innerHTML =
        "<thead><tr><th>Tháng</th><th class='ta-r'>Tổng doanh thu</th><th class='ta-r'>Doanh thu Vé</th><th class='ta-r'>Doanh thu F&B</th><th class='ta-r'>Vé bán</th></tr></thead>";
      var tb = el("tbody");
      monthly.forEach(function (m) {
        tb.appendChild(
          el("tr", {}, [
            el("td", {}, [m.label || "—"]),
            el("td", { class: "ta-r", style: "font-weight:700;color:#dc2626;" }, [hub.fmtMoney(m.revenue || 0)]),
            el("td", { class: "ta-r" }, [hub.fmtMoney(m.ticketRevenue != null ? m.ticketRevenue : m.revenue)]),
            el("td", { class: "ta-r", style: "color:#059669;font-weight:600;" }, [hub.fmtMoney(m.fnbRevenue || 0)]),
            el("td", { class: "ta-r" }, [String(m.ticketsSold || 0)]),
          ]),
        );
      });
      tbl.appendChild(tb);
      chartCard.appendChild(tbl);
    }
    row2.appendChild(chartCard);

    // Booking breakdown card (stacked bar)
    var breakdownCard = el("div", {
      class: "ws-card ws-dashboard-breakdown-card",
    });
    breakdownCard.appendChild(
      el("h3", { class: "ws-dashboard-card-title" }, ["Phân tích kênh bán vé"]),
    );
    if (data.bookingBreakdown) {
      var bd = data.bookingBreakdown;
      var totalCh = (bd.online || 0) + (bd.pos || 0);
      var totalAdvance = (bd.advance || 0) + (bd.walkIn || 0);
      breakdownCard.appendChild(
        el("div", { class: "ws-dashboard-channel-row" }, [
          el("span", { class: "ws-dashboard-channel-label" }, [
            "Kênh online vs POS",
          ]),
          renderStackedBar(
            [
              { label: "Online", value: bd.online || 0, color: "#3b82f6" },
              { label: "POS", value: bd.pos || 0, color: "#d9363e" },
            ],
            totalCh,
          ),
        ]),
      );
      breakdownCard.appendChild(
        el("div", { class: "ws-dashboard-channel-row" }, [
          el("span", { class: "ws-dashboard-channel-label" }, [
            "Đặt trước vs Đặt tại quầy",
          ]),
          renderStackedBar(
            [
              { label: "Đặt trước", value: bd.advance || 0, color: "#10b981" },
              { label: "Tại quầy", value: bd.walkIn || 0, color: "#f59e0b" },
            ],
            totalAdvance,
          ),
        ]),
      );
    } else {
      breakdownCard.appendChild(
        el("div", { class: "ws-dashboard-empty" }, ["Chưa có dữ liệu."]),
      );
    }
    row2.appendChild(breakdownCard);
    viewRoot.appendChild(row2);

    // ----- Hàng 3: Recent bookings + Today's showtimes -----
    var row3 = el("div", { class: "ws-dashboard-row" });

    // Recent bookings
    var recentCard = el("div", { class: "ws-card ws-dashboard-recent-card" });
    recentCard.appendChild(
      el("h3", { class: "ws-dashboard-card-title" }, ["Booking gần đây"]),
    );
    var recent = data.recentBookings || [];
    if (!recent.length) {
      recentCard.appendChild(
        el("div", { class: "ws-dashboard-empty" }, [
          "Chưa có booking nào trong kỳ.",
        ]),
      );
    } else {
      var rtbl = el("table", { class: "ws-table" });
      rtbl.innerHTML =
        '<thead><tr><th>Mã vé</th><th>Khách</th><th>Phim</th><th>Suất</th><th class="ta-r">Tiền</th><th>Trạng thái</th></tr></thead>';
      var rtb = el("tbody");
      recent.slice(0, 8).forEach(function (b) {
        var statusCls = "ws-badge ";
        if (b.status === "CONFIRMED") statusCls += "ws-badge-ok";
        else if (b.status === "PENDING") statusCls += "ws-badge-warn";
        else if (b.status === "CANCELLED" || b.status === "USED")
          statusCls += "ws-badge-mute";
        rtb.appendChild(
          el("tr", {}, [
            el("td", {}, [b.ticketCode || "—"]),
            el("td", {}, [b.customerName || "—"]),
            el("td", {}, [b.movieTitle || "—"]),
            el("td", {}, [b.showtimeStart || "—"]),
            el("td", { class: "ta-r" }, [hub.fmtMoney(b.amount || 0)]),
            el("td", {}, [el("span", { class: statusCls }, [b.status || "—"])]),
          ]),
        );
      });
      rtbl.appendChild(rtb);
      recentCard.appendChild(rtbl);
    }
    row3.appendChild(recentCard);

    // Today's showtimes
    var todayCard = el("div", { class: "ws-card ws-dashboard-today-card" });
    todayCard.appendChild(
      el("h3", { class: "ws-dashboard-card-title" }, ["Suất chiếu hôm nay"]),
    );
    var today = data.todaysShowtimes || [];
    if (!today.length) {
      todayCard.appendChild(
        el("div", { class: "ws-dashboard-empty" }, [
          "Không có suất chiếu hôm nay.",
        ]),
      );
    } else {
      var ttbl = el("table", { class: "ws-table" });
      ttbl.innerHTML =
        "<thead><tr><th>Phim</th><th>Chi nhánh</th><th>Phòng</th><th>Giờ</th></tr></thead>";
      var ttb = el("tbody");
      today.slice(0, 8).forEach(function (s) {
        ttb.appendChild(
          el("tr", {}, [
            el("td", {}, [s.movieTitle || "—"]),
            el("td", {}, [s.branchName || "—"]),
            el("td", {}, [s.screenName || "—"]),
            el("td", {}, [s.startTime || "—"]),
          ]),
        );
      });
      ttbl.appendChild(ttb);
      todayCard.appendChild(ttbl);
    }
    row3.appendChild(todayCard);
    viewRoot.appendChild(row3);
  }

  /**
   * Vẽ bar chart đơn giản bằng SVG (không cần thư viện ngoài).
   * monthly: [{label, revenue, ticketsSold}]
   */
  function renderMonthlyRevenueChart(monthly) {
    var maxRev = 0;
    monthly.forEach(function (m) {
      if ((m.revenue || 0) > maxRev) maxRev = m.revenue;
    });
    if (maxRev <= 0) maxRev = 1;

    var W = 560,
      H = 180,
      PAD_L = 48,
      PAD_R = 16,
      PAD_T = 16,
      PAD_B = 36;
    var innerW = W - PAD_L - PAD_R;
    var innerH = H - PAD_T - PAD_B;
    var n = Math.max(1, monthly.length);
    var slotW = innerW / n;
    var barW = Math.min(38, slotW * 0.6);

    var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.setAttribute("preserveAspectRatio", "xMidYMid meet");
    svg.setAttribute("class", "ws-dashboard-svg-chart");
    svg.setAttribute("aria-label", "Doanh thu theo tháng");

    // Trục Y (0%, 50%, 100%)
    for (var p = 0; p <= 4; p++) {
      var ratio = p / 4;
      var y = PAD_T + innerH * (1 - ratio);
      var line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      line.setAttribute("x1", PAD_L);
      line.setAttribute("x2", W - PAD_R);
      line.setAttribute("y1", y);
      line.setAttribute("y2", y);
      line.setAttribute("class", "ws-dashboard-svg-grid");
      svg.appendChild(line);
      var valTxt = document.createElementNS(
        "http://www.w3.org/2000/svg",
        "text",
      );
      valTxt.setAttribute("x", PAD_L - 6);
      valTxt.setAttribute("y", y + 4);
      valTxt.setAttribute("text-anchor", "end");
      valTxt.setAttribute("class", "ws-dashboard-svg-axis");
      valTxt.textContent =
        ratio === 1
          ? hub.fmtMoney(maxRev)
          : ratio === 0
            ? "0"
            : hub.fmtMoney(Math.round(maxRev * ratio));
      svg.appendChild(valTxt);
    }

    // Bars
    monthly.forEach(function (m, idx) {
      var cx = PAD_L + slotW * idx + slotW / 2;
      var h = innerH * ((m.revenue || 0) / maxRev);
      var y = PAD_T + innerH - h;
      var rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      rect.setAttribute("x", cx - barW / 2);
      rect.setAttribute("y", y);
      rect.setAttribute("width", barW);
      rect.setAttribute("height", Math.max(2, h));
      rect.setAttribute("rx", 3);
      rect.setAttribute("class", "ws-dashboard-svg-bar");
      // Tooltip
      var titleEl = document.createElementNS(
        "http://www.w3.org/2000/svg",
        "title",
      );
      titleEl.textContent =
        (m.label || "") + ": " + hub.fmtMoney(m.revenue || 0);
      rect.appendChild(titleEl);
      svg.appendChild(rect);

      // Label trục X
      var xt = document.createElementNS("http://www.w3.org/2000/svg", "text");
      xt.setAttribute("x", cx);
      xt.setAttribute("y", H - PAD_B + 18);
      xt.setAttribute("text-anchor", "middle");
      xt.setAttribute("class", "ws-dashboard-svg-axis");
      xt.textContent = m.label || "";
      svg.appendChild(xt);
    });

    return svg;
  }

  /**
   * Stacked horizontal bar — dùng để trực quan hoá tỉ lệ kênh bán.
   * segments: [{label, value, color}], total: tổng (>=0).
   */
  function renderStackedBar(segments, total) {
    var wrap = el("div", { class: "ws-dashboard-stacked-wrap" });
    var bar = el("div", { class: "ws-dashboard-stacked-bar" });
    var sum =
      total ||
      segments.reduce(function (a, s) {
        return a + (s.value || 0);
      }, 0);
    if (sum <= 0) {
      wrap.appendChild(
        el("div", { class: "ws-dashboard-empty" }, ["Chưa có dữ liệu."]),
      );
      return wrap;
    }
    segments.forEach(function (s) {
      var pct = Math.round(((s.value || 0) / sum) * 100);
      if (pct <= 0) return;
      var seg = el("div", {
        class: "ws-dashboard-stacked-seg",
        style: "width:" + pct + "%;background:" + s.color,
        title: s.label + ": " + s.value + " (" + pct + "%)",
      });
      bar.appendChild(seg);
    });
    wrap.appendChild(bar);
    var legend = el("div", { class: "ws-dashboard-stacked-legend" });
    segments.forEach(function (s) {
      var pct = Math.round(((s.value || 0) / sum) * 100);
      legend.appendChild(
        el("span", { class: "ws-dashboard-legend-item" }, [
          el("span", {
            class: "ws-dashboard-legend-swatch",
            style: "background:" + s.color,
          }),
          el("span", {}, [s.label + ": "]),
          el("strong", {}, [String(s.value || 0) + " (" + pct + "%)"]),
        ]),
      );
    });
    wrap.appendChild(legend);
    return wrap;
  }

  // ============ PROFILE ============
  function moduleProfile() {
    loading();
    // Load /api/session trước để biết role, sau đó chọn endpoint phù hợp.
    // /api/profile chỉ dành cho CUSTOMER (ProfileController.requireCustomer).
    // ADMIN/MANAGER/STAFF dùng /api/session (luôn public, trả role + user info).
    hub
      .getSession()
      .then(function (session) {
        var endpoint =
          session.role === "CUSTOMER" ? "/api/profile" : "/api/session";
        return hub.api(endpoint).then(function (data) {
          return { endpoint: endpoint, data: data, session: session };
        });
      })
      .then(function (resp) {
        var data = resp.data;
        if (resp.endpoint === "/api/profile") {
          var box = card("Ho so cua toi", "Thong tin tai khoan Customer.");
          var tbl = el("table", { class: "ws-table" });
          tbl.innerHTML =
            "<thead><tr><th>Truong</th><th>Gia tri</th></tr></thead>";
          var tb = el("tbody");
          var u = (data && data.user) || {};
          [
            "id",
            "email",
            "phone",
            "fullName",
            "role",
            "status",
            "createdAt",
          ].forEach(function (f) {
            tb.appendChild(
              el("tr", {}, [
                el("td", {}, [f]),
                el("td", {}, [String(u[f] || "")]),
              ]),
            );
          });
          tbl.appendChild(tb);
          box.appendChild(tbl);
          viewRoot.replaceChildren(box);
        } else {
          // ADMIN/MANAGER/STAFF: dùng dữ liệu từ /api/session (csrfToken, userId, username, role)
          var box = card(
            "Ho so dang nhap",
            "Tai khoan dang nhap vao khu vuc quan ly.",
          );
          var tbl = el("table", { class: "ws-table" });
          tbl.innerHTML =
            "<thead><tr><th>Truong</th><th>Gia tri</th></tr></thead>";
          var tb = el("tbody");
          ["userId", "email", "username", "role", "csrfToken"].forEach(
            function (f) {
              tb.appendChild(
                el("tr", {}, [
                  el("td", {}, [f]),
                  el("td", {}, [String(data[f] || "")]),
                ]),
              );
            },
          );
          tbl.appendChild(tb);
          box.appendChild(
            el(
              "p",
              { class: "muted", style: "margin-top:12px;font-size:0.85rem" },
              [
                "Tai khoan quan ly su dung endpoint /api/session (cong khai). De sua thong tin ca nhan hay lien he Admin.",
              ],
            ),
          );
          box.appendChild(tbl);
          viewRoot.replaceChildren(box);
        }
      })
      .catch(errorState);
  }

  function moduleProfileInternal() {
    // Giữ alias cho code cũ, delegate sang moduleProfile (auto-detect role).
    return moduleProfile();
  }

  // ============ DISCOVERY (customer) ============
  function moduleDiscovery() {
    var box = card(
      "Lich chieu dang mo ban",
      "Suat chieu thuoc chi nhanh hoat dong, phim dang phat hanh, khach khong can dang nhap.",
    );
    viewRoot.replaceChildren(box);

    var bar = el("div", { class: "toolbar" });
    var branchSel = el("select", { name: "branchId" }, [
      el("option", { value: "" }, ["Tat ca chi nhanh"]),
    ]);
    var movieSel = el("select", { name: "movieId" }, [
      el("option", { value: "" }, ["Tat ca phim"]),
    ]);
    var dateInput = el("input", {
      type: "date",
      name: "date",
      value: params.get("date") || "",
    });
    var applyBtn = el("button", { class: "btn", type: "button" }, [
      "Tim suat chieu",
    ]);
    var resetBtn = el(
      "a",
      { class: "btn secondary", href: hub.ctx + "/console?module=discovery" },
      ["Xoa loc"],
    );

    bar.appendChild(
      el("label", {}, [el("span", {}, ["Chi nhanh"]), branchSel]),
    );
    bar.appendChild(el("label", {}, [el("span", {}, ["Phim"]), movieSel]));
    bar.appendChild(el("label", {}, [el("span", {}, ["Ngay"]), dateInput]));
    bar.appendChild(applyBtn);
    bar.appendChild(resetBtn);
    box.appendChild(bar);

    var results = el("div", { id: "discoveryResults" });
    box.appendChild(results);
    results.appendChild(
      el("div", { class: "empty-state" }, [
        el("p", {}, ['Chon chi nhanh/phim/ngay roi bam "Tim suat chieu".']),
      ]),
    );

    hub
      .api("/branch")
      .then(function (br) {
        (Array.isArray(br) ? br : br.items || []).forEach(function (b) {
          branchSel.appendChild(
            el("option", { value: String(b.id) }, [b.name || "#" + b.id]),
          );
        });
      })
      .catch(function () {
        /* ignore */
      });

    // Customer accounts cannot access the internal movie catalog endpoint.
    // The public discovery endpoint still supports browsing without this
    // optional filter, so avoid generating expected 403 console errors.
    if (role !== "CUSTOMER") {
      hub
        .api("/movie")
        .then(function (mv) {
          var list = Array.isArray(mv) ? mv : mv.items || [];
          // Newest first: release_date DESC, id DESC (same as MovieDAO.findAll).
          list.sort(function (a, b) {
            var ad = a && a.releaseDate ? new Date(a.releaseDate).getTime() : 0;
            var bd = b && b.releaseDate ? new Date(b.releaseDate).getTime() : 0;
            if (ad !== bd) return bd - ad;
            return (b && b.id ? b.id : 0) - (a && a.id ? a.id : 0);
          });
          list.forEach(function (m) {
            movieSel.appendChild(
              el("option", { value: String(m.id) }, [m.title || "#" + m.id]),
            );
          });
        })
        .catch(function () {
          /* ignore */
        });
    }

    function load() {
      results.replaceChildren(
        el("div", { class: "loading-state" }, [el("p", {}, ["Dang tai..."])]),
      );
      var qs = new URLSearchParams();
      if (branchSel.value) qs.set("branchId", branchSel.value);
      if (movieSel.value) qs.set("movieId", movieSel.value);
      if (dateInput.value) qs.set("date", dateInput.value);
      hub
        .api("/showtime/discovery" + (qs.toString() ? "?" + qs.toString() : ""))
        .then(function (list) {
          var rows = Array.isArray(list) ? list : [];
          results.replaceChildren();
          if (!rows.length) {
            results.appendChild(
              el("div", { class: "empty-state" }, [
                el("h3", {}, ["Khong co suat chieu"]),
                el("p", {}, ["Thu bo loc khac."]),
              ]),
            );
            return;
          }
          rows.forEach(function (s) {
            var row = el("article", { class: "show-row" });
            row.innerHTML =
              "<b>" +
              escapeHtml(s.movieTitle || "-") +
              "</b>" +
              "<div><span>" +
              escapeHtml(s.branchName || "-") +
              " - " +
              escapeHtml(s.screenName || "-") +
              " - " +
              hub.fmtDateTime(s.startTime) +
              "</span></div>";
            row.appendChild(
              el("strong", {}, [
                String(s.availableSeats != null ? s.availableSeats : "-") +
                  " ghe trong",
              ]),
            );
            if (role === "CUSTOMER") {
              if (s.showtimePassed) {
                row.appendChild(
                  el(
                    "span",
                    { class: "btn small disabled", "aria-disabled": "true" },
                    ["Da qua gio chieu"],
                  ),
                );
              } else {
                row.appendChild(
                  el(
                    "a",
                    {
                      class: "btn small",
                      href:
                        hub.ctx +
                        "/booking?showtimeId=" +
                        encodeURIComponent(s.id),
                    },
                    ["Dat ve"],
                  ),
                );
              }
            }
            results.appendChild(row);
          });
        })
        .catch(function (e) {
          results.replaceChildren(
            el("div", { class: "flash flash-err" }, [e.message || "Loi"]),
          );
        });
    }

    applyBtn.addEventListener("click", function () {
      load();
    });
    if (dateInput.value) load();
  }

  // ============ SEAT (customer) ============
  function moduleSeat() {
    var showtimeId = params.get("showtimeId");
    if (!showtimeId) {
      emptyState(
        "Chua chon suat chieu",
        "Vui long chon mot suat chieu tu trang Lich chieu.",
        "Quay lai lich chieu",
        hub.ctx + "/console?module=discovery",
      );
      return;
    }
    // The public booking page contains the complete hold/payment flow.
    // Keep this legacy route as a compatibility redirect instead of exposing
    // an incomplete seat-only screen.
    window.location.replace(
      hub.ctx + "/booking?showtimeId=" + encodeURIComponent(showtimeId),
    );
    return;
    var box = card("Chon ghe", "Chon ghe ngoi cho suat chieu cua ban.");
    viewRoot.replaceChildren(box);
    var seatArea = el("div", { class: "seat-area" });
    box.appendChild(seatArea);
    seatArea.appendChild(
      el("div", { class: "loading-state" }, [
        el("p", {}, ["Dang tai so do ghe..."]),
      ]),
    );
    hub
      .api("/showtime/" + showtimeId)
      .then(function (showtimeData) {
        var st = showtimeData.showtime;
        var seats = showtimeData.seats || [];
        seatArea.replaceChildren();
        seatArea.appendChild(
          el("div", {}, [
            el("h3", {}, [st.movieTitle || "Phim"]),
            el("p", {}, [
              (st.branchName || "") +
                " - " +
                (st.screenName || "") +
                " - " +
                hub.fmtDateTime(st.startTime),
            ]),
          ]),
        );
        var grid = el("div", { class: "seat-grid" });
        grid.appendChild(
          el("div", { class: "screen-indicator" }, ["MAN HINH"]),
        );
        var rows = {};
        seats.forEach(function (s) {
          var r = s.rowLabel || "A";
          if (!rows[r]) rows[r] = [];
          rows[r].push(s);
        });
        Object.keys(rows)
          .sort()
          .forEach(function (r) {
            var rowEl = el("div", { class: "seat-row" });
            rowEl.appendChild(el("small", {}, [r]));
            var wrap = el("div");
            var occupiedThrough = 0;
            rows[r]
              .sort(function (a, b) {
                return (a.colNo || 0) - (b.colNo || 0);
              })
              .forEach(function (seat) {
                var seatCol = Number(seat.colNo) || 0;
                if (seatCol <= occupiedThrough) return;
                var seatCode =
                  String(seat.rowLabel) +
                  seatCol +
                  (seat.seatType === "COUPLE" ? "-" + (seatCol + 1) : "");
                var btn = el("button", { class: "seat", type: "button" }, [
                  seatCode,
                ]);
                btn.disabled = seat.status !== "AVAILABLE";
                if (seat.seatType === "VIP") btn.classList.add("vip");
                if (seat.seatType === "COUPLE") btn.classList.add("couple");
                wrap.appendChild(btn);
                if (seat.seatType === "COUPLE") occupiedThrough = seatCol + 1;
              });
            rowEl.appendChild(wrap);
            grid.appendChild(rowEl);
          });
        seatArea.appendChild(grid);
      })
      .catch(function (e) {
        seatArea.replaceChildren(
          el("div", { class: "flash flash-err" }, [e.message || "Loi"]),
        );
      });
  }

  // ============ VALIDATE (scan ticket) ============
  function moduleValidate() {
    var box = card(
      "Soát vé",
      "Nhập mã vé hoặc dùng đầu đọc QR hỗ trợ bàn phím để check-in khách.",
    );
    viewRoot.replaceChildren(box);

    // ---- Toolbar: input + nút check-in + nút clear lịch sử
    var input = el("input", {
      type: "text",
      placeholder: "Nhập hoặc quét mã vé (ticket code)...",
      class: "pos-input",
      style: "flex:1;min-width:240px;padding:10px;text-transform:uppercase",
      autocomplete: "off",
      autofocus: "autofocus",
    });
    var btn = el("button", { class: "ws-btn primary", type: "button" }, [
      "Check-in",
    ]);
    var clearBtn = el("button", { class: "ws-btn secondary", type: "button" }, [
      "Xóa lịch sử",
    ]);
    var inputRow = el("div", { class: "ticket-scan-input-row" }, [
      input,
      btn,
      clearBtn,
    ]);
    box.appendChild(inputRow);

    // ---- Khu vực hiển thị kết quả check-in lớn
    var result = el("div", { class: "ticket-scan-result" });
    box.appendChild(result);

    // ---- Lịch sử check-in trong session (chỉ trong memory, reset khi reload)
    var historyWrap = el("div", { class: "ticket-scan-history-wrap" });
    historyWrap.appendChild(
      el("h3", { class: "ticket-scan-history-title" }, [
        "Lịch sử check-in hôm nay",
      ]),
    );
    var historyList = el("div", { class: "ticket-scan-history-list" });
    historyWrap.appendChild(historyList);
    var checkedTickets = [];

    function renderHistory() {
      historyList.replaceChildren();
      if (!checkedTickets.length) {
        historyList.appendChild(
          el("div", { class: "ticket-scan-history-empty" }, [
            "Chưa có vé nào được check-in.",
          ]),
        );
        return;
      }
      var ol = el("ol", { class: "ticket-scan-history-ol" });
      checkedTickets
        .slice()
        .reverse()
        .forEach(function (it) {
          var li = el(
            "li",
            {
              class:
                "ticket-scan-history-item ticket-scan-history-" + it.outcome,
            },
            [
              el("span", { class: "ticket-scan-history-code" }, [it.code]),
              el("span", { class: "ticket-scan-history-meta" }, [it.label]),
              el("span", { class: "ticket-scan-history-time" }, [it.time]),
            ],
          );
          ol.appendChild(li);
        });
      historyList.appendChild(ol);
    }
    renderHistory();
    box.appendChild(historyWrap);

    function setBusy(busy) {
      btn.disabled = busy;
      input.disabled = busy;
      btn.textContent = busy ? "Đang xử lý..." : "Check-in";
    }

    function doCheckIn() {
      var raw = (input.value || "").trim();
      if (!raw) {
        input.focus();
        return;
      }
      var code = raw.toUpperCase();
      setBusy(true);
      result.className = "ticket-scan-result";
      result.innerHTML = "";
      hub
        .api("/booking/validate", {
          method: "POST",
          body: { ticketCode: code },
        })
        .then(function (ticket) {
          setBusy(false);
          // Hiển thị vé thành công (xanh) — thông tin rõ ràng cho staff
          result.className = "ticket-scan-result ticket-scan-result-ok";
          result.innerHTML = "";
          var heading = el("div", { class: "ticket-scan-result-heading" }, [
            "CHECK-IN THÀNH CÔNG",
          ]);
          result.appendChild(heading);
          result.appendChild(
            el("div", { class: "ticket-scan-result-code" }, [
              ticket.ticketCode || code,
            ]),
          );
          var grid = el("div", { class: "ticket-scan-result-grid" });
          function row(label, value) {
            grid.appendChild(
              el("div", { class: "ticket-scan-result-row" }, [
                el("span", { class: "ticket-scan-result-label" }, [label]),
                el("span", { class: "ticket-scan-result-value" }, [
                  value || "—",
                ]),
              ]),
            );
          }
          row("Trạng thái", ticket.status || "USED");
          row("Mã vé", ticket.ticketCode || code);
          row("Khách hàng", ticket.customerName || ticket.userName || "—");
          row("Phim", ticket.movieTitle || "—");
          row("Suất chiếu", ticket.showtimeStart || "—");
          row("Chi nhánh", ticket.branchName || "—");
          row("Phòng", ticket.screenName || "—");
          var seatCodes = Array.isArray(ticket.seats)
            ? ticket.seats
                .map(function (seat) {
                  return seat.seatCode || seat.rowLabel + seat.colNo;
                })
                .join(", ")
            : "";
          row("Ghế", seatCodes || "—");
          row(
            "Tổng tiền",
            ticket.totalAmount != null ? hub.fmtMoney(ticket.totalAmount) : "—",
          );
          result.appendChild(grid);

          checkedTickets.push({
            code: ticket.ticketCode || code,
            label:
              (ticket.movieTitle || "—") +
              " · " +
              (seatCodes || "Không có thông tin ghế"),
            outcome: "ok",
            time: new Date().toLocaleTimeString("vi-VN", {
              hour: "2-digit",
              minute: "2-digit",
              second: "2-digit",
            }),
          });
          renderHistory();
          hub.notify("Đã check-in vé " + (ticket.ticketCode || code), "ok");
          input.value = "";
          input.focus();
        })
        .catch(function (e) {
          setBusy(false);
          result.className = "ticket-scan-result ticket-scan-result-err";
          result.innerHTML = "";
          result.appendChild(
            el("div", { class: "ticket-scan-result-heading" }, [
              "CHECK-IN THẤT BẠI",
            ]),
          );
          result.appendChild(
            el("div", { class: "ticket-scan-result-code" }, [code]),
          );
          result.appendChild(
            el("div", { class: "ticket-scan-result-error" }, [
              (e && (e.message || e.code)) || "Không rõ lỗi",
            ]),
          );

          checkedTickets.push({
            code: code,
            label: ((e && e.code) || "Lỗi") + " · " + ((e && e.message) || ""),
            outcome: "err",
            time: new Date().toLocaleTimeString("vi-VN", {
              hour: "2-digit",
              minute: "2-digit",
              second: "2-digit",
            }),
          });
          renderHistory();
          hub.notify((e && e.message) || "Check-in thất bại", "err");
        });
    }

    btn.addEventListener("click", doCheckIn);
    // Bấm Enter để submit — quan trọng với scanner bắn Enter sau khi quét
    input.addEventListener("keydown", function (ev) {
      if (ev.key === "Enter") {
        ev.preventDefault();
        doCheckIn();
      }
    });
    clearBtn.addEventListener("click", function () {
      checkedTickets = [];
      renderHistory();
      result.innerHTML = "";
      result.className = "ticket-scan-result";
      input.focus();
    });
  }

  // ============ Pickup (customer) ============
  function modulePickup() {
        var box = card('Nhan don online', 'Nhap pickup code de nhan don bap nuoc.');
        viewRoot.replaceChildren(box);
        var inp = el('input', { type: 'text', placeholder: 'Pickup code...', class: 'pos-input', style: 'width:300px;padding:10px' });
        var btn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Xac nhan']);
        var flash = el('div', { class: 'flash', style: 'display:none' });
        box.appendChild(el('div', { style: 'display:flex;gap:10px;margin-top:10px' }, [inp, btn, flash]));
        btn.addEventListener('click', function () {
            var code = (inp.value || '').trim();
            if (!code) return;
            flash.style.display = 'block';
            flash.className = 'flash';
            flash.textContent = 'Dang xu ly...';
            hub.api('/concession/pickup', { method: 'POST', body: { pickupCode: code } }).then(function () {
                flash.className = 'flash flash-ok';
                flash.textContent = 'Da giao thanh cong.';
            }).catch(function (e) {
                flash.className = 'flash flash-err';
                flash.textContent = e.message || 'Loi';
            });
        });
    }

    // ============ POS (delegate to pos-staff.js) ============
  function modulePosTicket() {
    if (window.modulePosTicket) return window.modulePosTicket();
    emptyModule("Ban ve tai quay", "Module POS chua san sang.");
  }

<<<<<<< HEAD
  function modulePosConcession() {
    if (window.modulePosConcession) return window.modulePosConcession();
    emptyModule("POS bap nuoc", "Module POS chua san sang.");
  }
=======
    // ============ MODULE DISPATCH MAP ============
    var MODULES = {
        profile: function () {
            if (role !== 'CUSTOMER') return moduleProfileInternal();
            var handler = dispatchBusiness('profile');
            if (handler) return handler();
            return moduleProfile();
        },
        discovery: moduleDiscovery,
        reviews: function () {
            var handler = dispatchBusiness('reviews');
            return (handler && handler()) || emptyModule('Đánh giá phim', 'Chức năng đánh giá chưa sẵn sàng.');
        },
        support: function () {
            var handler = dispatchBusiness('support');
            return (handler && handler()) || emptyModule('Hỗ trợ', 'Chức năng chưa sẵn sàng.');
        },
        'support-inbox': function () {
            var handler = dispatchBusiness('support-inbox');
            return (handler && handler()) || emptyModule('Hỗ trợ', 'Chức năng chưa sẵn sàng.');
        },
        complaints: function () {
            var handler = dispatchBusiness('complaints');
            return (handler && handler()) || emptyModule('Khiếu nại', 'Chức năng chưa sẵn sàng.');
        },
        branch: function () { var h = dispatchBusiness('branch'); return (h && h()) || emptyModule('Chi nhanh', 'Chuc nang chua sang'); },
        movie: function () { var h = dispatchBusiness('movie'); return (h && h()) || emptyModule('Phim', 'Chuc nang chua sang'); },
        genres: function () { var h = dispatchBusiness('genres'); return (h && h()) || emptyModule('Thể loại phim', 'Chức năng chưa sẵn sàng'); },
        screen: function () { var h = dispatchBusiness('screen'); return (h && h()) || emptyModule('Phong chieu', 'Chuc nang chua sang'); },
        showtime: function () { var h = dispatchBusiness('showtime'); return (h && h()) || emptyModule('Lich chieu', 'Chuc nang chua sang'); },
        booking: function () { var h = dispatchBusiness('booking'); return (h && h()) || emptyModule('Dat ve', 'Chuc nang chua sang'); },
        ticket: function () {
            if (typeof window.moduleTicketStaff === 'function') return window.moduleTicketStaff();
            return emptyModule('Ve', 'Chuc nang chua sang');
        },
        pricing: function () { var h = dispatchBusiness('pricing'); return (h && h()) || emptyModule('Khung gia', 'Chuc nang chua sang'); },
        notification: function () { var h = dispatchBusiness('notification'); return (h && h()) || emptyModule('Thong bao', 'Chuc nang chua sang'); },
        'pos-ticket': modulePosTicket,
        seat: moduleSeat,
        'my-tickets': function () {
            // Customer tickets are registered as a form-page renderer, not in the
            // legacy CinemaHubPages registry used by the other console modules.
            var formPages = window.CinemaHubFormPages || {};
            if (formPages.booking && typeof formPages.booking.list === 'function') {
                formPages.booking.list();
                return;
            }
            emptyModule('Ve cua toi', 'Chuc nang chua sang');
        },
        // Reviews.
    };
>>>>>>> vu

  // ============ MODULE DISPATCH MAP ============
  var MODULES = {
    dashboard: moduleDashboard,
    profile: function () {
      if (role !== "CUSTOMER") return moduleProfileInternal();
      var handler = dispatchBusiness("profile");
      if (handler) return handler();
      return moduleProfile();
    },
    discovery: moduleDiscovery,
    reviews: function () {
      var handler = dispatchBusiness("reviews");
      return (
        (handler && handler()) ||
        emptyModule("Đánh giá phim", "Chức năng đánh giá chưa sẵn sàng.")
      );
    },
    branch: function () {
      var h = dispatchBusiness("branch");
      return (h && h()) || emptyModule("Chi nhanh", "Chuc nang chua sang");
    },
    movie: function () {
      var h = dispatchBusiness("movie");
      return (h && h()) || emptyModule("Phim", "Chuc nang chua sang");
    },
    genres: function () {
      var h = dispatchBusiness("genres");
      return (
        (h && h()) || emptyModule("Thể loại phim", "Chức năng chưa sẵn sàng")
      );
    },
    screen: function () {
      var h = dispatchBusiness("screen");
      return (h && h()) || emptyModule("Phong chieu", "Chuc nang chua sang");
    },
    showtime: function () {
      var h = dispatchBusiness("showtime");
      return (h && h()) || emptyModule("Lich chieu", "Chuc nang chua sang");
    },
    product: function () {
      var h = dispatchBusiness("product");
      return (h && h()) || emptyModule("San pham", "Chuc nang chua sang");
    },
    pricing: function () {
      var h = dispatchBusiness("pricing");
      return (h && h()) || emptyModule("Khung gia", "Chuc nang chua sang");
    },
    inventory: function () {
      var h = dispatchBusiness("inventory");
      return (h && h()) || emptyModule("Ton kho", "Chuc nang chua sang");
    },
    notification: function () {
      var h = dispatchBusiness("notification");
      return (h && h()) || emptyModule("Thong bao", "Chuc nang chua sang");
    },
    "pos-ticket": modulePosTicket,
    "pos-concession": modulePosConcession,
    pickup: modulePickup,
    validate: function () {
      if (role !== "BRANCH_STAFF") {
        return emptyModule(
          "Soát vé",
          "Chức năng này chỉ dành cho nhân viên chi nhánh.",
        );
      }
      return moduleValidate();
    },
    seat: moduleSeat,
    "my-tickets": function () {
      // Customer tickets are registered as a form-page renderer, not in the
      // legacy CinemaHubPages registry used by the other console modules.
      var formPages = window.CinemaHubFormPages || {};
      if (formPages.booking && typeof formPages.booking.list === "function") {
        formPages.booking.list();
        return;
      }
      emptyModule("Ve cua toi", "Chuc nang chua sang");
    },
    ticket: function () {
      var formPages = window.CinemaHubFormPages || {};
      if (formPages.booking && typeof formPages.booking.list === "function") {
        formPages.booking.list();
        return;
      }
      emptyModule("Lịch sử vé", "Chức năng chưa sẵn sàng.");
    },
    "my-orders": function () {
      var h = dispatchBusiness("concession");
      return (h && h()) || emptyModule("Don bap nuoc", "Chuc nang chua sang");
    },
    "fnb-catalog": function () {
      // Ưu tiên form-page renderer (CinemaHubFormPages['fnb-catalog'].list)
      // vì module này được đăng ký qua router.register. Hỗ trợ cả
      // legacy CinemaHubPages['fnb-catalog'] để tương thích ngược.
      var formPages = window.CinemaHubFormPages || {};
      if (
        formPages["fnb-catalog"] &&
        typeof formPages["fnb-catalog"].list === "function"
      ) {
        formPages["fnb-catalog"].list();
        return;
      }
      var h = dispatchBusiness("fnb-catalog");
      if (h) {
        h();
        return;
      }
      emptyModule("F&B", "Chuc nang chua sang");
    },
    "fnb-cart": function () {
      var formPages = window.CinemaHubFormPages || {};
      if (
        formPages["fnb-cart"] &&
        typeof formPages["fnb-cart"].list === "function"
      ) {
        formPages["fnb-cart"].list();
        return;
      }
      var h = dispatchBusiness("fnb-cart");
      if (h) {
        h();
        return;
      }
      emptyModule("F&B", "Chuc nang chua sang");
    },
    "fnb-orders": function () {
      var formPages = window.CinemaHubFormPages || {};
      if (formPages["fnb-orders"]) {
        if (params.get("action") === "view" && params.get("id")) {
          if (typeof formPages["fnb-orders"].view === "function") {
            formPages["fnb-orders"].view(params.get("id"));
            return;
          }
        } else if (typeof formPages["fnb-orders"].list === "function") {
          formPages["fnb-orders"].list();
          return;
        }
      }
      var h = dispatchBusiness("fnb-orders");
      if (h) {
        h();
        return;
      }
      emptyModule("F&B", "Chuc nang chua sang");
    },
    "fnb-checkout": function () {
      var formPages = window.CinemaHubFormPages || {};
      if (
        formPages["fnb-checkout"] &&
        typeof formPages["fnb-checkout"].list === "function"
      ) {
        formPages["fnb-checkout"].list();
        return;
      }
      emptyModule("F&B", "Chuc nang chua sang");
    },
    "concession-orders": function () {
      var h = dispatchBusiness("concession-orders");
      return (h && h()) || emptyModule("Don F&B", "Chuc nang chua sang");
    },
    "concession-refunds": function () {
      var h = dispatchBusiness("concession-refunds");
      return (h && h()) || emptyModule("Hoàn tiền F&B", "Chuc nang chua sang");
    },
    "concession-pickup": function () {
      var h = dispatchBusiness("concession-pickup");
      return (h && h()) || emptyModule("Quet ma F&B", "Chuc nang chua sang");
    },
    "customer-points": function () {
      var h = dispatchBusiness("customer-points");
      return (
        (h && h()) || emptyModule("Diem khach hang", "Chuc nang chua sang")
      );
    },
    // Reviews / Support / Complaints (RSC-2..4)
    support: function () {
      var h = dispatchBusiness("support");
      return (h && h()) || emptyModule("Ho tro", "Chuc nang chua sang");
    },
    "support-inbox": function () {
      var h = dispatchBusiness("support-inbox");
      return (h && h()) || emptyModule("Ho tro", "Chuc nang chua sang");
    },
    complaints: function () {
      var h = dispatchBusiness("complaints");
      return (h && h()) || emptyModule("Khieu nai", "Chuc nang chua sang");
    },
  };

  // ============ ROUTER ============
  function updateActiveNavigation(moduleName) {
    document
      .querySelectorAll("a.ws-menu-link, a.sb-link")
      .forEach(function (link) {
        var href = link.getAttribute("href");
        if (!href) return;
        var linkedModule =
          new URL(href, window.location.href).searchParams.get("module") ||
          "dashboard";
        var active = linkedModule === moduleName;
        link.classList.toggle("active", active);
        if (active) {
          link.setAttribute("aria-current", "page");
        } else {
          link.removeAttribute("aria-current");
        }
      });

    // Đồng bộ trạng thái Menu Accordion Kho hàng khi chuyển module
    var acc = document.querySelector(".ws-menu-accordion");
    var isInventory = moduleName === "inventory";
    if (acc) {
      var toggle = acc.querySelector(".ws-menu-accordion-toggle");
      if (!isInventory) {
        acc.classList.remove("open");
        if (toggle) {
          toggle.classList.remove("active");
          toggle.setAttribute("aria-expanded", "false");
        }
        document.querySelectorAll(".ws-menu-sub-link").forEach(function (sub) {
          sub.classList.remove("active");
        });
      } else {
        acc.classList.add("open");
        if (toggle) {
          toggle.classList.add("active");
          toggle.setAttribute("aria-expanded", "true");
        }
        var curTab =
          new URL(window.location.href).searchParams.get("tab") || "product";
        document.querySelectorAll(".ws-menu-sub-link").forEach(function (sub) {
          sub.classList.toggle(
            "active",
            sub.getAttribute("data-tab") === curTab,
          );
        });
      }
    }
  }
  window.CinemaHubUpdateActiveNavigation = updateActiveNavigation;

  function route(mod) {
    currentModule = mod;
    updateActiveNavigation(mod);
    if (topbarActions) topbarActions.replaceChildren();

    // Luôn clear viewRoot trước khi dispatch module để tránh hiển thị chồng
    // chất nội dung module cũ (vd. dashboard) với module mới (form-page) —
    // form-page render async qua Promise.all nên phải xóa ngay tại đây.
    loading();

    // Đồng bộ topbar theo module hiện tại ngay khi chuyển trang để tránh topbar
    // bị stuck ở 'Bảng điều khiển' trong khi viewRoot đang hiển thị form/list.
    // setWorkspaceHeader trong form-page.js / list-page.js sẽ cập nhật lại với
    // title cụ thể của từng page (vd. 'Thêm phim mới').
    if (hub && typeof hub.setWorkspaceHeader === "function") {
      var headerTitle = mod === "dashboard" ? "Bảng điều khiển" : "";
      hub.setWorkspaceHeader(headerTitle);
    }

    // Uu tien CinemaHubRouter (form-page modules): xu ly ?module=X&action=Y&id=Z
    if (window.CinemaHubRouter && window.CinemaHubRouter.autoRoute()) {
      viewRoot.setAttribute("aria-busy", "false");
      return;
    }

    var fn = MODULES[mod] || moduleDashboard;
    try {
      var result = fn();
      // Neu tra ve cleanup function, luu lai
      if (typeof result === "function") {
        window.__currentCleanup = result;
      } else if (result instanceof Node) {
        // Auto-mount: các module đặt ngoài (modules-voucher.js, modules-rsc.js…)
        // trả về DOM node mà không tự gắn vào viewRoot — gắn giúp chúng render.
        viewRoot.replaceChildren(result);
      }
    } catch (e) {
      errorState(e);
    }
    viewRoot.setAttribute("aria-busy", "false");
  }

  // Cleanup khi chuyen module
  window.addEventListener("popstate", function () {
    if (typeof window.__currentCleanup === "function") {
      window.__currentCleanup();
      window.__currentCleanup = null;
    }
    var activeModule =
      new URLSearchParams(window.location.search).get("module") || "dashboard";
    route(activeModule);
  });

  // ---------- customer sidebar ----------
  var customerSidebar = document.getElementById("customerSidebar");
  var customerSidebarToggle = document.getElementById("customerSidebarToggle");
  var customerSidebarBackdrop = document.getElementById(
    "customerSidebarBackdrop",
  );

  function setCustomerSidebarOpen(open) {
    if (!customerSidebar || !customerSidebarToggle || !customerSidebarBackdrop)
      return;
    customerSidebar.classList.toggle("open", open);
    customerSidebarBackdrop.classList.toggle("open", open);
    customerSidebarBackdrop.hidden = !open;
    customerSidebarToggle.setAttribute("aria-expanded", String(open));
  }

  if (customerSidebarToggle) {
    customerSidebarToggle.addEventListener("click", function () {
      setCustomerSidebarOpen(!customerSidebar.classList.contains("open"));
    });
  }
  if (customerSidebarBackdrop) {
    customerSidebarBackdrop.addEventListener("click", function () {
      setCustomerSidebarOpen(false);
    });
  }

  // ---------- sidebar nav click ----------
  document.addEventListener("click", function (e) {
    var a = e.target.closest
      ? e.target.closest("a.ws-menu-link, a.sb-link, a.ws-menu-sub-link")
      : null;
    if (!a) return;
    var href = a.getAttribute("href");
    if (!href) return;
    var m = href.match(/[?&]module=([^&]+)/);
    if (m) {
      e.preventDefault();
      history.pushState({}, "", href);
      var tab = new URL(href, window.location.href).searchParams.get("tab");
      if (
        m[1] === "inventory" &&
        currentModule === "inventory" &&
        typeof window.CinemaHubSwitchInventoryTab === "function"
      ) {
        updateActiveNavigation("inventory");
        window.CinemaHubSwitchInventoryTab(tab || "product");
      } else {
        route(m[1]);
      }
      setCustomerSidebarOpen(false);
    }
  });

  // modules-form-pages.js, etc.) have had a chance to register their pages first.
  document.addEventListener("DOMContentLoaded", function () {
    route(currentModule);
  });
})();
