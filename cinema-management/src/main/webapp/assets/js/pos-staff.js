// POS_COUNTER_SALE_AND_CONCESSION_FOR_STAFF
// Hai module workspace cho BRANCH_STAFF:
//  * modulePosTicket()      — Req 7.7 + bundle F&B: bán vé tại quầy kèm bắp nước combo,
//                              tra cứu điểm khách thành viên, áp voucher.
//  * modulePosConcession()  — Req 20.1, 20.3: tạo đơn POS bắp nước + tra cứu điểm + voucher.
// Cả hai dùng API đã có, không phụ thuộc ngoài hub.
// CHỈ DÙNG .then()/.catch() — KHÔNG async/await.
(function () {
  "use strict";
  if (window.__CinemaHubPosStaffLoaded) return;
  window.__CinemaHubPosStaffLoaded = true;

  // ---------- Local helpers ----------
  function todayIso() {
    var d = new Date();
    var m = String(d.getMonth() + 1);
    if (m.length < 2) m = "0" + m;
    var dd = String(d.getDate());
    if (dd.length < 2) dd = "0" + dd;
    return d.getFullYear() + "-" + m + "-" + dd;
  }

  function el(tag, attrs, children) {
    return window.CinemaHub
      ? window.CinemaHub.el(tag, attrs, children)
      : domEl(tag, attrs, children);
  }
  function domEl(tag, attrs, children) {
    var n = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === "class") n.className = attrs[k];
      else if (k === "html") n.innerHTML = attrs[k];
      else if (k === "style") n.setAttribute("style", attrs[k]);
      else if (k.indexOf("on") === 0 && typeof attrs[k] === "function")
        n.addEventListener(k.slice(2), attrs[k]);
      else if (attrs[k] != null) n.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) {
      n.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return n;
  }
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
  function fmtMoney(v) {
    try {
      return window.CinemaHub.fmtMoney(v);
    } catch (e) {
      return Number(v || 0).toLocaleString("vi-VN") + " đ";
    }
  }
  function fmtTime(s) {
    try {
      return window.CinemaHub.fmtDateTime(s);
    } catch (e) {
      return String(s || "")
        .replace("T", " ")
        .slice(0, 16);
    }
  }
  function wrapField(label, input) {
    return el("label", { class: "pos-field" }, [
      el("span", {}, [label]),
      input,
    ]);
  }
  function setOrderTitle(card, title) {
    var h = card.querySelector("h3");
    if (h) h.textContent = title;
  }
  function wrapContent(title, content) {
    return el("section", { class: "ws-card" }, [
      el("h3", { class: "ws-card-title" }, [title]),
      content,
    ]);
  }
  function wrap(title, content, klass) {
    var card = el("section", { class: "ws-card " + (klass || "") }, [
      el("h3", { class: "ws-card-title" }, [title]),
    ]);
    var body = el("div", { class: "pos-card-body" });
    if (content) body.appendChild(content);
    else
      body.appendChild(
        el("div", { class: "ws-empty" }, [el("p", {}, ["Chưa có nội dung."])]),
      );
    card.appendChild(body);
    return card;
  }
  function show(node) {
    if (node) node.classList.remove("hidden-card");
  }
  function confirmCounterPayment(
    hub,
    method,
    amount,
    description,
    onConfirm,
    onCancel,
  ) {
    var reference =
      method === "BANK_TRANSFER"
        ? "CH" +
          Date.now().toString(36).toUpperCase() +
          Math.random().toString(36).substring(2, 8).toUpperCase()
        : null;
    function openDialog(qr) {
      var previousFocus = document.activeElement;
      var methodLabel =
        method === "BANK_TRANSFER" ? "Chuyển khoản ngân hàng" : "Tiền mặt";
      var overlay = el("div", {
        class: "ws-modal-overlay",
        role: "presentation",
      });
      var backdrop = el("div", { class: "ws-modal-backdrop" });
      var panel = el("div", {
        class: "ws-modal-panel",
        role: "alertdialog",
        "aria-modal": "true",
        "aria-labelledby": "pos-payment-title",
        "aria-describedby": "pos-payment-description",
      });
      panel.style.maxWidth = "440px";
      var head = el("div", { class: "ws-modal-head" }, [
        el("h2", { id: "pos-payment-title" }, ["Xác nhận thanh toán tại quầy"]),
      ]);
      var body = el("div", { class: "ws-modal-body" }, [
        el("p", { id: "pos-payment-description" }, [description]),
        el("div", { class: "pos-payment-confirm-method" }, [
          el("span", {}, ["Phương thức"]),
          el("strong", {}, [methodLabel]),
        ]),
        el("div", { class: "pos-payment-confirm-total" }, [
          el("span", {}, ["Tổng cần thu"]),
          el("strong", {}, [fmtMoney(amount)]),
        ]),
      ]);
      if (method === "BANK_TRANSFER") {
        body.insertBefore(
          el("div", { class: "pos-payment-qr-wrap" }, [
            el("img", {
              class: "pos-payment-qr",
              src: qr.qrDataUrl,
              alt: "Mã QR chuyển khoản CinemaHub",
            }),
            el("p", { class: "pos-payment-qr-instruction" }, [
              "Khách quét QR để chuyển khoản. Nhân viên kiểm tra tiền đã vào tài khoản trước khi xác nhận.",
            ]),
            el("div", { class: "pos-payment-bank-details" }, [
              el("strong", {}, [qr.accountName]),
              el("span", {}, ["Số tài khoản: " + qr.accountNumber]),
              el("span", {}, ["Nội dung: " + qr.reference]),
            ]),
          ]),
          body.children[1],
        );
      }
      var cancel = el("button", { class: "ws-btn secondary", type: "button" }, [
        "Quay lại",
      ]);
      var confirm = el("button", { class: "ws-btn primary", type: "button" }, [
        "Đã nhận thanh toán",
      ]);
      var footer = el("div", { class: "ws-form-actions" }, [cancel, confirm]);
      var closed = false;

      function close() {
        if (closed) return;
        closed = true;
        document.removeEventListener("keydown", onKeyDown);
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        if (previousFocus && typeof previousFocus.focus === "function")
          previousFocus.focus();
      }
      function onKeyDown(event) {
        if (event.key === "Escape") {
          close();
          if (typeof onCancel === "function") onCancel();
        }
      }

      cancel.addEventListener("click", function () {
        close();
        if (typeof onCancel === "function") onCancel();
      });
      confirm.addEventListener("click", function () {
        if (closed) return;
        close();
        onConfirm(reference);
      });
      overlay.addEventListener("click", function (event) {
        if (event.target === overlay || event.target === backdrop) {
          close();
          if (typeof onCancel === "function") onCancel();
        }
      });
      panel.appendChild(head);
      panel.appendChild(body);
      panel.appendChild(footer);
      overlay.appendChild(backdrop);
      overlay.appendChild(panel);
      document.body.appendChild(overlay);
      document.addEventListener("keydown", onKeyDown);
      cancel.focus();
    }

    if (method !== "BANK_TRANSFER") {
      openDialog(null);
      return;
    }
    if (!Number.isSafeInteger(Number(amount)) || Number(amount) <= 0) {
      hub.notify(
        "Số tiền chuyển khoản phải lớn hơn 0. Hãy chọn tiền mặt cho đơn 0đ.",
        "warn",
      );
      if (typeof onCancel === "function") onCancel();
      return;
    }
    hub
      .api(
        "/payment/counter-qr?amount=" +
          encodeURIComponent(String(amount)) +
          "&reference=" +
          encodeURIComponent(reference),
      )
      .then(openDialog)
      .catch(function (error) {
        hub.notify(error.message || "Không thể tạo mã QR chuyển khoản.", "err");
        if (typeof onCancel === "function") onCancel();
      });
  }

  function showCounterSaleReceipt(title, codes) {
    var overlay = el("div", {
      class: "ws-modal-overlay",
      role: "presentation",
    });
    var backdrop = el("div", { class: "ws-modal-backdrop" });
    var panel = el("div", {
      class: "ws-modal-panel",
      role: "dialog",
      "aria-modal": "true",
      "aria-labelledby": "pos-receipt-title",
    });
    panel.style.maxWidth = "520px";
    var closeButton = el(
      "button",
      {
        class: "ws-btn secondary",
        type: "button",
        "aria-label": "Đóng",
      },
      ["Đóng"],
    );
    panel.appendChild(
      el("div", { class: "ws-modal-head" }, [
        el("h2", { id: "pos-receipt-title" }, [title]),
      ]),
    );
    var body = el("div", { class: "ws-modal-body" }, [
      el("p", {}, [
        "Khách đưa mã cho nhân viên quét tại cửa phòng chiếu hoặc quầy nhận bắp nước. Có thể nhập mã thủ công nếu cần.",
      ]),
    ]);
    codes.forEach(function (item) {
      if (!item.code) return;
      var codeBox = el(
        "div",
        {
          class: "pos-sale-code",
          style:
            "padding:14px;margin:12px 0;text-align:center;background:#fff;border:1px solid #e5e7eb;border-radius:10px",
        },
        [
          el("strong", { style: "display:block;margin-bottom:8px" }, [
            item.label,
          ]),
          el("canvas", {
            class: "ticket-barcode",
            width: "320",
            height: "64",
            style: "display:block;max-width:100%;height:auto;margin:8px auto",
          }),
          el(
            "code",
            { style: "font-size:1.1rem;font-weight:700;letter-spacing:.08em" },
            [item.code],
          ),
        ],
      );
      body.appendChild(codeBox);
      window.CinemaHub.renderBarcode(
        codeBox.querySelector("canvas"),
        item.code,
      );
    });
    var footer = el("div", { class: "ws-form-actions" }, [closeButton]);

    function close() {
      document.removeEventListener("keydown", onKeyDown);
      if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
    }
    function onKeyDown(event) {
      if (event.key === "Escape") close();
    }
    closeButton.addEventListener("click", close);
    overlay.addEventListener("click", function (event) {
      if (event.target === overlay || event.target === backdrop) close();
    });
    panel.append(body, footer);
    overlay.append(backdrop, panel);
    document.body.appendChild(overlay);
    document.addEventListener("keydown", onKeyDown);
    closeButton.focus();
  }

  // ============================================================
  // Tra cứu khách hàng thành viên theo email HOẶC phone → points/tier
  // ============================================================
  function lookupCustomer(hub, email, phone, onDone) {
    var em = (email || "").trim();
    var ph = (phone || "").trim();
    if (!em && !ph) {
      onDone(null, "Nhập email hoặc số điện thoại");
      return;
    }
    var qs = "";
    if (em) qs = "email=" + encodeURIComponent(em);
    else qs = "phone=" + encodeURIComponent(ph);
    hub
      .api("/loyalty/customer-points?" + qs)
      .then(function (info) {
        onDone(info, null);
      })
      .catch(function (err) {
        onDone(null, err && err.message ? err.message : "Không tìm thấy khách");
      });
  }

  // Render box thông tin khách + điểm (dùng chung pos-ticket + pos-concession)
  function renderCustomerBadge(info) {
    var tier = (info && info.tier) || "STANDARD";
    var tierClass = "pos-tier pos-tier-" + String(tier).toLowerCase();
    var nameEl = info.fullName
      ? el("div", { class: "pos-customer-name" }, [info.fullName])
      : el("div", { class: "pos-customer-name" }, [
          info.email || info.phone || "Khách hàng",
        ]);
    var metaEl = el("div", { class: "pos-customer-meta" });
    if (info.email) metaEl.appendChild(el("span", {}, [info.email]));
    if (info.phone)
      metaEl.appendChild(
        el("span", {}, [(info.email ? " · " : "") + formatPhone(info.phone)]),
      );
    var wrapNode = el("div", { class: "pos-customer-badge" }, [
      nameEl,
      metaEl,
      el("div", { class: "pos-customer-points" }, [
        el("span", { class: tierClass }, [String(tier)]),
        el("strong", {}, [String(info.points || 0)]),
        el("span", {}, [" điểm"]),
      ]),
    ]);
    return wrapNode;
  }

  /** Format phone để hiển thị: 84xxx → 0xxx */
  function formatPhone(p) {
    if (!p) return "";
    return p.startsWith("84") ? "0" + p.substring(2) : p;
  }

  // ============================================================
  // POS TICKET — Counter sale + F&B bundle + điểm + voucher
  // ============================================================
  function modulePosTicket() {
    var hub = window.CinemaHub;
    var viewRoot = document.getElementById("viewRoot");
    if (!hub || !viewRoot) return Promise.resolve();

    var heading = el("div", { class: "ws-page-heading" }, [
      el("span", { class: "eyebrow" }, ["Counter sale"]),
      el("h1", {}, ["POS - Bán vé tại quầy"]),
      el("p", {}, [
        "Chọn chiếu, ghế, ưu đãi và phương thức thanh toán tiền mặt hoặc chuyển khoản tại quầy.",
      ]),
    ]);

    var toolbar = el("div", { class: "ws-toolbar" });
    var branchSel = el("select", { class: "pos-input" }, [
      el("option", { value: "" }, ["-- Chọn chi nhánh --"]),
    ]);
    var branchField = el("label", { class: "pos-field" }, [
      el("span", {}, ["Chi nhánh"]),
      branchSel,
    ]);
    var dateInput = el("input", {
      type: "date",
      value: todayIso(),
      class: "pos-input",
    });
    var movieInp = el("input", {
      type: "text",
      placeholder: "Lọc phim (tùy chọn)",
      class: "pos-input",
    });
    var reloadBtn = el("button", { class: "ws-btn primary", type: "button" }, [
      "Tải suất chiếu",
    ]);
    toolbar.appendChild(branchField);
    toolbar.appendChild(
      el("label", { class: "pos-field" }, [
        el("span", {}, ["Ngày"]),
        dateInput,
      ]),
    );
    toolbar.appendChild(
      el("label", { class: "pos-field" }, [el("span", {}, ["Phim"]), movieInp]),
    );
    toolbar.appendChild(reloadBtn);

    var showtimeList = el("div", { class: "pos-showtime-list" });
    var seatCard = wrap("Sơ đồ ghế", null, "pos-seat-card hidden-card");
    var orderCard = wrap(
      "Phiếu bán vé (vé + bắp nước)",
      null,
      "pos-order-card hidden-card",
    );

    showtimeList.appendChild(
      el("div", { class: "ws-empty" }, [
        el("p", {}, ['Chọn chi nhánh / ngày rồi bấm "Tải suất chiếu".']),
      ]),
    );

    var root = el("div", {}, [
      heading,
      toolbar,
      showtimeList,
      seatCard,
      orderCard,
    ]);
    viewRoot.replaceChildren(root);

    var currentShowtimes = [];
    var concessionProducts = []; // danh sách product cache
    var selectedCart = []; // giỏ F&B [{productId, name, unitPrice, qty}]
    var customerInfo = null; // {id, email, fullName, points, tier}
    var voucherInfo = null; // {code, discountType, ...} sau khi lookup
    var voucherCode = "";
    var pointsToRedeem = 0;

    // Branch scope: Staff chỉ có đúng 1 chi nhánh được phân công → hiển thị
    // tên chi nhánh dạng read-only thay vì dropdown (tránh chọn nhầm / lọc sai).
    // Admin / Manager có thể chọn nhiều chi nhánh → giữ dropdown.
    var roleMeta = (document.querySelector('meta[name="user-role"]') || {})
      .content;
    hub
      .api("/branch/my-location")
      .then(function (res) {
        var items =
          res && Array.isArray(res.items)
            ? res.items
            : Array.isArray(res)
              ? res
              : [];
        if (roleMeta === "BRANCH_STAFF" && items.length === 1) {
          branchSel.replaceChildren(
            el("option", { value: String(items[0].id) }, [
              items[0].name || "Chi nhánh #" + items[0].id,
            ]),
          );
          branchSel.value = String(items[0].id);
          branchSel.disabled = true;
          branchField.querySelector("span").textContent = "Chi nhánh của bạn";
          branchSel.style.background = "#f3f4f6";
          branchSel.style.color = "#4b5563";
          return;
        }
        items.forEach(function (b) {
          branchSel.appendChild(
            el("option", { value: String(b.id) }, [
              b.name || "Branch #" + b.id,
            ]),
          );
        });
        if (items.length === 1) branchSel.value = String(items[0].id);
      })
      .catch(function () {
        /* ignore */
      });

    // Preload products để bundle F&B
    hub
      .api("/concession/products")
      .then(function (data) {
        concessionProducts = Array.isArray(data) ? data : [];
      })
      .catch(function () {
        /* ignore */
      });

    reloadBtn.addEventListener("click", function () {
      loadShowtimes();
    });
    movieInp.addEventListener("input", function () {
      if (currentShowtimes.length) renderShowtimes(currentShowtimes);
    });

    function loadShowtimes() {
      if (!branchSel.value) {
        hub.notify("Hãy chọn chi nhánh trước", "warn");
        return;
      }
      showtimeList.replaceChildren(
        el("div", { class: "ws-empty" }, [el("p", {}, ["Đang tải..."])]),
      );
      var qs = new URLSearchParams();
      qs.set("branchId", branchSel.value);
      qs.set("date", dateInput.value);
      hub
        .api("/showtime/discovery?" + qs.toString())
        .then(function (data) {
          currentShowtimes = Array.isArray(data) ? data : [];
          renderShowtimes(currentShowtimes);
        })
        .catch(function (e) {
          showtimeList.replaceChildren(
            el("div", { class: "ws-empty" }, [
              el("h3", {}, ["Lỗi tải suất chiếu"]),
              el("p", {}, [e.message || ""]),
            ]),
          );
        });
    }

    function renderShowtimes(list) {
      var q = (movieInp.value || "").trim().toLowerCase();
      var rows = q
        ? list.filter(function (s) {
            return (s.movieTitle || "").toLowerCase().indexOf(q) >= 0;
          })
        : list;
      showtimeList.replaceChildren();
      if (!rows.length) {
        showtimeList.appendChild(
          el("div", { class: "ws-empty" }, [
            el("h3", {}, ["Không có suất chiếu"]),
            el("p", {}, ["Thử đổi ngày hoặc chi nhánh khác."]),
          ]),
        );
        return;
      }
      var table = el("table", { class: "ws-table" });
      table.innerHTML =
        "<thead><tr><th>Phim</th><th>Phòng</th><th>Giờ</th><th>Ghế trống</th><th>Giá</th><th></th></tr></thead>";
      var tbody = el("tbody");
      rows.forEach(function (s) {
        var tr = document.createElement("tr");
        tr.innerHTML =
          "<td><strong>" +
          escapeHtml(s.movieTitle || "-") +
          "</strong></td>" +
          "<td>" +
          escapeHtml(s.screenName || "-") +
          "</td>" +
          "<td>" +
          escapeHtml((s.startTime || "").replace("T", " ").slice(0, 16)) +
          "</td>" +
          "<td>" +
          (s.availableSeats != null ? Number(s.availableSeats) : "-") +
          "</td>" +
          "<td>" +
          fmtMoney(s.standardPrice) +
          "</td>" +
          "<td></td>";
        var btn = el(
          "button",
          { class: "ws-btn small primary", type: "button" },
          ["Chọn ghế"],
        );
        var td = tr.lastChild;
        td.appendChild(btn);
        if (s.showtimePassed) {
          tr.classList.add("row-disabled");
          btn.disabled = true;
          btn.textContent = "Đã chiếu";
        } else {
          btn.addEventListener("click", function () {
            selectShowtime(s);
          });
        }
        tbody.appendChild(tr);
      });
      table.appendChild(tbody);
      showtimeList.appendChild(wrapContent("Suất chiếu khả dụng", table));
    }

    function selectShowtime(st) {
      show(seatCard);
      show(orderCard);
      var body = seatCard.querySelector(".pos-card-body");
      body.replaceChildren(
        el("div", { class: "ws-empty" }, [
          el("p", {}, ["Đang tải sơ đồ ghế..."]),
        ]),
      );
      setOrderTitle(
        seatCard,
        "Ghế - " + (st.movieTitle || "") + " - " + fmtTime(st.startTime),
      );

      hub
        .api("/showtime/" + st.id)
        .then(function (detail) {
          var seats = (detail && detail.seats) || [];
          var showtimeInfo = Object.assign({}, st, {
            seatPrices: (detail && detail.seatPrices) || st.seatPrices || {},
          });
          renderSeatMap(body, seats, st, showtimeInfo);
        })
        .catch(function (e) {
          body.replaceChildren(
            el("div", { class: "flash flash-err" }, [
              e.message || "Lỗi tải sơ đồ",
            ]),
          );
        });
    }

    function renderSeatMap(host, seats, st, showtimeInfo) {
      host.replaceChildren();
      var area = el("div", { class: "pos-seat-area" });
      var grid = el("div", { class: "pos-seat-grid" });
      grid.appendChild(el("div", { class: "pos-screen" }, ["-- MÀN HÌNH --"]));
      var rows = {};
      seats.forEach(function (s) {
        var r = s.rowLabel || "A";
        if (!rows[r]) rows[r] = [];
        rows[r].push(s);
      });
      var selected = [];
      function rerenderOrder() {
        renderOrder(
          showtimeInfo,
          selected,
          selectedCart,
          customerInfo,
          voucherInfo,
          function () {
            selectedCart = [];
            customerInfo = null;
            voucherInfo = null;
            voucherCode = "";
            pointsToRedeem = 0;
            loadShowtimes();
          },
        );
      }

      Object.keys(rows)
        .sort()
        .forEach(function (r) {
          var rowEl = el("div", { class: "pos-seat-row" });
          rowEl.appendChild(el("span", { class: "pos-row-label" }, [r]));
          var container = el("div", { class: "pos-seats" });
          var occupiedThrough = 0;
          rows[r]
            .slice()
            .sort(function (a, b) {
              return (a.colNo || 0) - (b.colNo || 0);
            })
            .forEach(function (seat) {
              var seatCol = Number(seat.colNo) || 0;
              if (seatCol <= occupiedThrough) return;
              var cls =
                "pos-seat " +
                (seat.status === "AVAILABLE"
                  ? "available"
                  : seat.status === "HOLD"
                    ? "hold"
                    : "sold");
              if (seat.seatType === "VIP") cls += " vip";
              if (seat.seatType === "COUPLE") cls += " couple";
              var seatCode =
                String(seat.rowLabel) +
                seat.colNo +
                (seat.seatType === "COUPLE" ? "-" + (seatCol + 1) : "");
              var btn = el("button", { class: cls, type: "button" }, [
                seatCode,
              ]);
              if (seat.status !== "AVAILABLE") btn.disabled = true;
              btn.addEventListener("click", function () {
                var idx = selected.findIndex(function (x) {
                  return x.seatId === seat.seatId;
                });
                if (idx >= 0) {
                  selected.splice(idx, 1);
                  btn.classList.remove("selected");
                } else {
                  if (selected.length >= 8) {
                    hub.notify("Tối đa 8 ghế/giao dịch", "warn");
                    return;
                  }
                  selected.push(seat);
                  btn.classList.add("selected");
                }
                rerenderOrder();
              });
              container.appendChild(btn);
              if (seat.seatType === "COUPLE") occupiedThrough = seatCol + 1;
            });
          rowEl.appendChild(container);
          grid.appendChild(rowEl);
        });
      area.appendChild(grid);
      area.appendChild(
        el("div", { class: "pos-seat-legend" }, [
          el("span", {}, [
            el("span", {
              class: "pos-seat available",
              style:
                "pointer-events:none;display:inline-block;width:18px;height:18px",
            }),
            " Trống",
          ]),
          el("span", {}, [
            el("span", {
              class: "pos-seat hold",
              style:
                "pointer-events:none;display:inline-block;width:18px;height:18px",
            }),
            " Đang giữ",
          ]),
          el("span", {}, [
            el("span", {
              class: "pos-seat sold",
              style:
                "pointer-events:none;display:inline-block;width:18px;height:18px",
            }),
            " Đã bán",
          ]),
          el("span", {}, [
            el("span", {
              class: "pos-seat selected",
              style:
                "pointer-events:none;display:inline-block;width:18px;height:18px",
            }),
            " Đã chọn",
          ]),
        ]),
      );
      host.appendChild(area);
      renderOrder(
        showtimeInfo,
        selected,
        selectedCart,
        customerInfo,
        voucherInfo,
        function () {
          loadShowtimes();
        },
      );
    }

    function renderOrder(st, selected, cart, cust, vouch, onSuccess) {
      orderCard.classList.remove("hidden-card");
      var body = orderCard.querySelector(".pos-card-body");
      body.replaceChildren();

      var summary = el("div", { class: "pos-order-summary" });
      summary.innerHTML =
        "<strong>" +
        escapeHtml(st.movieTitle || "-") +
        "</strong>" +
        '<div style="color:#6b7280;font-size:.84rem;margin-top:4px">' +
        escapeHtml(st.branchName || "") +
        " - " +
        escapeHtml(st.screenName || "") +
        " - " +
        fmtTime(st.startTime) +
        "</div>";
      body.appendChild(summary);

      // ----- Bảng ghế -----
      var table = el("table", { class: "ws-table" });
      table.innerHTML =
        "<thead><tr><th>Ghế</th><th>Loại</th><th>Đơn giá</th></tr></thead>";
      var tbody = el("tbody");
      var seatTotal = 0;
      if (!selected.length) {
        tbody.appendChild(
          el("tr", {}, [
            el("td", { colspan: "3", class: "muted" }, ["Chưa chọn ghế nào."]),
          ]),
        );
      } else {
        selected.forEach(function (seat) {
          var configuredPrice = st.seatPrices && st.seatPrices[seat.seatType];
          var base = Number(st.standardPrice || 0);
          var price =
            configuredPrice != null
              ? Number(configuredPrice)
              : seat.seatType === "VIP"
                ? Math.round(base * 1.3)
                : seat.seatType === "COUPLE"
                  ? Math.round(base * 1.6)
                  : base;
          var seatCode =
            String(seat.rowLabel) +
            seat.colNo +
            (seat.seatType === "COUPLE" ? "-" + (Number(seat.colNo) + 1) : "");
          seatTotal += price;
          tbody.appendChild(
            el("tr", {}, [
              el("td", {}, [seatCode]),
              el("td", {}, [seat.seatType || "STANDARD"]),
              el("td", {}, [fmtMoney(price)]),
            ]),
          );
        });
      }
      table.appendChild(tbody);
      body.appendChild(table);

      // ----- Tra cứu khách hàng -----
      body.appendChild(
        el("h4", { class: "pos-section-title" }, [
          "Khách hàng thành viên (tùy chọn)",
        ]),
      );
      var custEmailInp = el("input", {
        type: "email",
        placeholder: "Email khách",
        class: "pos-input",
      });
      var custPhoneInp = el("input", {
        type: "tel",
        placeholder: "Số điện thoại",
        class: "pos-input",
      });
      var custLookupBtn = el(
        "button",
        { class: "ws-btn secondary small", type: "button" },
        ["Tra cứu"],
      );
      var custBox = el("div", { class: "pos-customer-box" });
      if (cust) custBox.appendChild(renderCustomerBadge(cust));
      var custActions = el("div", { class: "pos-form-grid pos-form-grid-2" }, [
        wrapField("Email", custEmailInp),
        wrapField("Điện thoại", custPhoneInp),
        el("div", { class: "pos-field" }, [
          el("span", {}, [" "]),
          custLookupBtn,
        ]),
      ]);
      body.appendChild(custActions);
      body.appendChild(custBox);
      function clearCustomerSelection() {
        customerInfo = null;
        cust = null;
        voucherInfo = null;
        vouch = null;
        pointsToRedeem = 0;
        voucherCode = "";
        voucherInput.value = "";
        voucherDiscount = 0;
        tierDiscount = 0;
        custBox.replaceChildren();
        if (pointsInput) {
          pointsInput.value = "0";
          pointsInput.disabled = true;
          maxRedeemable = 0;
        }
        if (sellBtn) sellBtn.disabled = !selected.length;
        if (pointsError) pointsError.textContent = "";
        voucherMessage.textContent = "";
        voucherMessage.className = "ws-field-error";
        updateOrderTotal();
      }
      custEmailInp.addEventListener("input", clearCustomerSelection);
      custPhoneInp.addEventListener("input", clearCustomerSelection);
      custLookupBtn.addEventListener("click", function () {
        custLookupBtn.disabled = true;
        custLookupBtn.textContent = "Đang tra...";
        lookupCustomer(
          hub,
          custEmailInp.value,
          custPhoneInp.value,
          function (info, err) {
            custLookupBtn.disabled = false;
            custLookupBtn.textContent = "Tra cứu";
            if (err) {
              hub.notify("Tra cứu: " + err, "warn");
              return;
            }
            customerInfo = info;
            cust = info;
            voucherInfo = null;
            pointsToRedeem = 0;
            voucherCode = "";
            custEmailInp.value = info.email || "";
            custPhoneInp.value = info.phone ? formatPhone(info.phone) : "";
            renderOrder(st, selected, cart, cust, null, onSuccess);
          },
        );
      });

      // ----- Bắp nước combo (giỏ) -----
      body.appendChild(
        el("h4", { class: "pos-section-title" }, [
          "Bắp nước / combo (tùy chọn)",
        ]),
      );
      var fbBox = el("div", { class: "pos-fb-area" });
      if (!concessionProducts.length) {
        fbBox.appendChild(
          el("div", { class: "muted" }, ["Đang tải sản phẩm..."]),
        );
      } else {
        var typeSel = el("select", { class: "pos-input" }, [
          el("option", { value: "" }, ["Tất cả"]),
          el("option", { value: "FOOD" }, ["Đồ ăn"]),
          el("option", { value: "DRINK" }, ["Nước uống"]),
          el("option", { value: "COMBO" }, ["Combo"]),
        ]);
        var grid = el("div", { class: "pos-fb-grid" });
        function renderFb() {
          grid.replaceChildren();
          var t = typeSel.value;
          var list = concessionProducts.filter(function (p) {
            return p.status !== "INACTIVE" && (!t || p.type === t);
          });
          if (!list.length) {
            grid.appendChild(
              el("div", { class: "muted" }, ["Không có sản phẩm."]),
            );
            return;
          }
          list.forEach(function (p) {
            var isCombo = p.type === "COMBO";
            var card = el("button", { class: "pos-fb-card", type: "button" });
            card.innerHTML =
              '<div class="pos-fb-name">' +
              escapeHtml(p.name || "-") +
              (isCombo ? ' <span class="pos-combo-tag">COMBO</span>' : "") +
              "</div>" +
              '<div class="pos-fb-price">' +
              fmtMoney(p.price) +
              (p.stock != null
                ? ' <span style="font-size:0.75rem;color:' +
                  (p.stock > 0 ? "#10b981" : "#ef4444") +
                  ';">(Còn ' +
                  p.stock +
                  ")</span>"
                : "") +
              "</div>";
            card.addEventListener("click", function () {
              var ex = cart.find(function (x) {
                return x.productId === p.id;
              });
              if (ex) ex.qty += 1;
              else
                cart.push({
                  productId: p.id,
                  name: p.name,
                  unitPrice: p.price,
                  qty: 1,
                  stock: p.stock,
                });
              renderOrder(st, selected, cart, cust, vouch, onSuccess);
            });
            grid.appendChild(card);
          });
        }
        typeSel.addEventListener("change", renderFb);
        renderFb();
        fbBox.appendChild(typeSel);
        fbBox.appendChild(grid);

        if (cart.length) {
          var ctbl = el("table", { class: "ws-table" });
          ctbl.innerHTML =
            '<thead><tr><th>Sản phẩm</th><th style="min-width:140px;text-align:center;">Số lượng</th><th>Thành tiền</th><th></th></tr></thead>';
          var ctb = el("tbody");
          cart.forEach(function (it, idx) {
            var tr = el("tr", {}, [
              el("td", {}, [
                el("strong", {}, [it.name]),
                el("div", { class: "muted" }, [fmtMoney(it.unitPrice)]),
              ]),
              el(
                "td",
                {
                  class: "qty-cell",
                  style: "text-align:center;white-space:nowrap;",
                },
                [
                  el(
                    "button",
                    {
                      class: "ws-btn small secondary qty-dec",
                      type: "button",
                      title: "Giảm 1",
                    },
                    ["-"],
                  ),
                  el("input", {
                    type: "number",
                    class: "ws-input qty-input",
                    min: "1",
                    max: "999",
                    step: "1",
                    value: String(it.qty),
                    style:
                      "width:68px;text-align:center;display:inline-block;margin:0 4px;padding:4px 6px;",
                    "aria-label": "Số lượng",
                  }),
                  el(
                    "button",
                    {
                      class: "ws-btn small secondary qty-inc",
                      type: "button",
                      title: "Tăng 1",
                    },
                    ["+"],
                  ),
                ],
              ),
              el(
                "td",
                { class: "line-total-cell", style: "font-weight:600;" },
                [fmtMoney(it.unitPrice * it.qty)],
              ),
              el("td", {}, [
                el(
                  "button",
                  {
                    class: "ws-btn small danger",
                    type: "button",
                    title: "Xóa món",
                  },
                  ["X"],
                ),
              ]),
            ]);
            var decBtn = tr.querySelector(".qty-dec");
            var incBtn = tr.querySelector(".qty-inc");
            var qInp = tr.querySelector(".qty-input");
            decBtn.addEventListener("click", function () {
              if (it.qty <= 1) cart.splice(idx, 1);
              else it.qty -= 1;
              renderOrder(st, selected, cart, cust, vouch, onSuccess);
            });
            incBtn.addEventListener("click", function () {
              if (it.qty < 999) it.qty += 1;
              renderOrder(st, selected, cart, cust, vouch, onSuccess);
            });
            qInp.addEventListener("change", function () {
              var v = parseInt(this.value, 10);
              if (isNaN(v) || v < 1) v = 1;
              if (v > 999) v = 999;
              it.qty = v;
              renderOrder(st, selected, cart, cust, vouch, onSuccess);
            });
            tr.lastChild.firstChild.addEventListener("click", function () {
              cart.splice(idx, 1);
              renderOrder(st, selected, cart, cust, vouch, onSuccess);
            });
            ctb.appendChild(tr);
          });
          ctbl.appendChild(ctb);
          fbBox.appendChild(ctbl);
        }
      }
      body.appendChild(fbBox);

      // ----- Voucher applies to ticket subtotal; points are validated server-side -----
      var concessionTotal = cart.reduce(function (s, it) {
        return s + it.unitPrice * it.qty;
      }, 0);
      var tierDiscount = estimateTierDiscount(cust, seatTotal);
      var afterTier = Math.max(0, seatTotal - tierDiscount);
      var voucherDiscount = vouch ? Number(vouch.discount || 0) : 0;
      var ticketPayable = Math.max(0, afterTier - voucherDiscount);
      var redemptionRate = cust ? Number(cust.redemptionRate || 100) : 100;
      var maxRedeemable =
        cust && redemptionRate > 0 ? Math.max(0, Number(cust.points || 0)) : 0;
      var initialPoints = Number(pointsToRedeem);
      var initialPointsValid =
        Number.isInteger(initialPoints) &&
        initialPoints >= 0 &&
        initialPoints <= maxRedeemable;
      var initialPointsDiscount = initialPointsValid
        ? initialPoints * redemptionRate
        : 0;

      body.appendChild(el("h4", { class: "pos-section-title" }, ["Ưu đãi vé"]));
      var voucherInput = el("input", {
        type: "text",
        class: "pos-input",
        placeholder: "Nhập mã voucher",
        value: voucherCode,
      });
      var applyVoucherBtn = el(
        "button",
        { class: "ws-btn secondary small", type: "button" },
        ["Áp dụng voucher"],
      );
      var voucherMessage = el("div", {
        class: "ws-field-error",
        "aria-live": "polite",
      });
      if (vouch) {
        voucherMessage.className = "ws-field-hint";
        voucherMessage.textContent =
          "Đã áp dụng " + vouch.code + " · giảm " + fmtMoney(voucherDiscount);
      }
      voucherInput.addEventListener("input", function () {
        voucherCode = voucherInput.value.trim();
        if (vouch) {
          voucherInfo = null;
          pointsToRedeem = 0;
          renderOrder(st, selected, cart, cust, null, onSuccess);
        }
        voucherMessage.textContent = "";
      });
      applyVoucherBtn.addEventListener("click", function () {
        voucherCode = voucherInput.value.trim();
        if (!voucherCode) {
          voucherMessage.textContent = "Nhập mã voucher trước khi áp dụng.";
          return;
        }
        applyVoucherBtn.disabled = true;
        hub
          .api(
            "/voucher/lookup?code=" +
              encodeURIComponent(voucherCode) +
              "&orderTotal=" +
              encodeURIComponent(String(afterTier)),
          )
          .then(function (result) {
            var preview = result && result.data ? result.data : result;
            if (!preview || !Number.isFinite(Number(preview.discount))) {
              throw new Error("Không đọc được thông tin voucher.");
            }
            voucherInfo = preview;
            voucherCode = preview.code || voucherCode;
            renderOrder(st, selected, cart, cust, voucherInfo, onSuccess);
          })
          .catch(function (error) {
            voucherMessage.textContent =
              error.message || "Không thể áp dụng voucher.";
          })
          .then(function () {
            applyVoucherBtn.disabled = false;
          });
      });
      body.appendChild(
        el("div", { class: "pos-form-grid pos-form-grid-2" }, [
          wrapField("Mã voucher", voucherInput),
          el("div", { class: "pos-field" }, [
            el("span", {}, [" "]),
            applyVoucherBtn,
          ]),
        ]),
      );
      body.appendChild(voucherMessage);

      var pointsInput = null;
      var pointsError = el("div", {
        class: "ws-field-error",
        "aria-live": "polite",
      });
      if (cust) {
        pointsInput = el("input", {
          type: "number",
          class: "pos-input",
          min: "0",
          max: String(maxRedeemable),
          step: "1",
          value: String(pointsToRedeem),
        });
        body.appendChild(
          el("div", { class: "pos-form-grid pos-form-grid-2" }, [
            wrapField(
              "Điểm muốn dùng (số dư " + maxRedeemable + ")",
              pointsInput,
            ),
            el("div", { class: "pos-field" }, [
              el("span", {}, ["Giảm từ điểm"]),
              el("strong", { class: "pos-points-preview" }, [
                fmtMoney(initialPointsDiscount),
              ]),
            ]),
          ]),
        );
        body.appendChild(pointsError);
        pointsInput.addEventListener("input", function () {
          pointsToRedeem =
            pointsInput.value === "" ? 0 : Number(pointsInput.value);
          if (
            !Number.isInteger(pointsToRedeem) ||
            pointsToRedeem < 0 ||
            pointsToRedeem > maxRedeemable
          ) {
            pointsError.textContent =
              "Nhập số nguyên từ 0 đến số dư " + maxRedeemable + " điểm.";
            sellBtn.disabled = true;
          } else {
            pointsError.textContent = "";
            sellBtn.disabled = !selected.length;
          }
          updateOrderTotal();
        });
      }

      // ----- Tổng kết + nút bán -----
      var summaryDiv = el("div", { class: "pos-order-totals" });
      summaryDiv.appendChild(
        el("div", { class: "pos-total-row" }, [
          el("span", {}, ["Tiền vé"]),
          el("strong", {}, [fmtMoney(seatTotal)]),
        ]),
      );
      if (concessionTotal > 0) {
        summaryDiv.appendChild(
          el("div", { class: "pos-total-row" }, [
            el("span", {}, ["Bắp nước/combo"]),
            el("strong", {}, [fmtMoney(concessionTotal)]),
          ]),
        );
      }
      var tierDiscountRow = null;
      if (tierDiscount > 0) {
        tierDiscountRow = el(
          "div",
          { class: "pos-total-row pos-total-discount" },
          [
            el("span", {}, ["Ưu đãi hạng (" + (cust.tier || "MEMBER") + ")"]),
            el("strong", {}, ["-" + fmtMoney(tierDiscount)]),
          ],
        );
        summaryDiv.appendChild(tierDiscountRow);
      }
      var voucherDiscountRow = null;
      if (voucherDiscount > 0) {
        voucherDiscountRow = el(
          "div",
          { class: "pos-total-row pos-total-discount" },
          [
            el("span", {}, ["Voucher"]),
            el("strong", {}, ["-" + fmtMoney(voucherDiscount)]),
          ],
        );
        summaryDiv.appendChild(voucherDiscountRow);
      }
      var pointsDiscount = initialPointsDiscount;
      if (pointsDiscount > 0) {
        summaryDiv.appendChild(
          el("div", { class: "pos-total-row pos-total-discount" }, [
            el("span", {}, ["Điểm đã dùng"]),
            el("strong", { class: "pos-points-discount-value" }, [
              "-" + fmtMoney(pointsDiscount),
            ]),
          ]),
        );
      }
      var finalTotal = Math.max(
        0,
        seatTotal -
          tierDiscount -
          voucherDiscount -
          pointsDiscount +
          concessionTotal,
      );
      var grandTotal = el("div", { class: "pos-order-total" }, [
        el("span", {}, ["TỔNG THANH TOÁN"]),
        el("strong", {}, [fmtMoney(finalTotal)]),
      ]);
      summaryDiv.appendChild(grandTotal);
      // Hint: số điểm sẽ cộng (ước tính 1 điểm / 1000đ)
      var earnedPointsRow = null;
      if (cust) {
        var estPoints = Math.floor(finalTotal / 1000);
        earnedPointsRow = el(
          "div",
          { class: "pos-total-row pos-total-points" },
          [
            el("span", {}, ["Điểm hiện tại / sau giao dịch"]),
            el("strong", {}, [
              (cust.points || 0) + " → ~" + ((cust.points || 0) + estPoints),
            ]),
          ],
        );
        summaryDiv.appendChild(earnedPointsRow);
      }
      body.appendChild(summaryDiv);

      var paySel = el("select", { class: "pos-input" }, [
        el("option", { value: "CASH" }, ["Tiền mặt"]),
      ]);
      body.appendChild(
        el("div", { class: "pos-form-grid" }, [
          wrapField("Phương thức thanh toán", paySel),
        ]),
      );

      var sellBtn = el("button", { class: "ws-btn primary", type: "button" }, [
        "Xác nhận thanh toán & bán vé",
      ]);
      sellBtn.disabled = !selected.length;
      if (pointsInput && !initialPointsValid) {
        pointsError.textContent =
          "Nhập số nguyên từ 0 đến số dư " + maxRedeemable + " điểm.";
        sellBtn.disabled = true;
      }
      function updateOrderTotal() {
        var usedPoints = Number(pointsInput ? pointsInput.value : 0);
        var safePoints =
          Number.isInteger(usedPoints) &&
          usedPoints >= 0 &&
          usedPoints <= maxRedeemable
            ? usedPoints
            : 0;
        var pointsPreview = body.querySelector(".pos-points-preview");
        if (pointsPreview)
          pointsPreview.textContent = fmtMoney(safePoints * redemptionRate);
        if (tierDiscountRow) {
          if (tierDiscount > 0)
            tierDiscountRow.querySelector("strong").textContent =
              "-" + fmtMoney(tierDiscount);
          else tierDiscountRow.remove();
        }
        if (voucherDiscountRow) {
          if (voucherDiscount > 0)
            voucherDiscountRow.querySelector("strong").textContent =
              "-" + fmtMoney(voucherDiscount);
          else voucherDiscountRow.remove();
        }
        if (earnedPointsRow && !cust) earnedPointsRow.remove();
        grandTotal.querySelector("strong").textContent = fmtMoney(
          Math.max(
            0,
            seatTotal -
              tierDiscount -
              voucherDiscount -
              safePoints * redemptionRate +
              concessionTotal,
          ),
        );
        if (pointsInput) {
          var valueEl = body.querySelector(".pos-points-discount-value");
          var discount = safePoints * redemptionRate;
          if (discount > 0 && !valueEl) {
            var row = el("div", { class: "pos-total-row pos-total-discount" }, [
              el("span", {}, ["Điểm đã dùng"]),
              el("strong", { class: "pos-points-discount-value" }, [
                "-" + fmtMoney(discount),
              ]),
            ]);
            summaryDiv.insertBefore(row, grandTotal);
          } else if (valueEl && discount > 0) {
            valueEl.textContent = "-" + fmtMoney(discount);
          } else if (valueEl) {
            valueEl.parentElement.remove();
          }
        }
      }
      sellBtn.addEventListener("click", function () {
        if (!selected.length) return;
        if (cart && cart.length) {
          var overStock = cart.find(function (item) {
            return item.stock != null && item.qty > item.stock;
          });
          if (overStock) {
            hub.notify(
              'Sản phẩm "' +
                overStock.name +
                '" không đủ tồn kho tại chi nhánh (hiện còn ' +
                overStock.stock +
                ", yêu cầu " +
                overStock.qty +
                "). Vui lòng giảm số lượng!",
              "warn",
            );
            return;
          }
        }
        var requestedPoints = Number(pointsInput ? pointsInput.value : 0);
        if (
          !Number.isInteger(requestedPoints) ||
          requestedPoints < 0 ||
          requestedPoints > maxRedeemable
        ) {
          pointsError.textContent =
            "Nhập số nguyên từ 0 đến số dư " + maxRedeemable + " điểm.";
          if (pointsInput) pointsInput.focus();
          return;
        }
        var payable = Math.max(
          0,
          seatTotal -
            tierDiscount -
            voucherDiscount -
            requestedPoints * redemptionRate +
            concessionTotal,
        );
        var payMethod = paySel.value;
        sellBtn.disabled = true;
        confirmCounterPayment(
          hub,
          payMethod,
          payable,
          cart.length
            ? "Xác nhận thu tiền cho vé và bắp nước/combo trong cùng giao dịch?"
            : "Xác nhận thu tiền cho giao dịch bán vé?",
          function (paymentReference) {
            sellBtn.disabled = true;
            sellBtn.textContent = "Đang xử lý...";
            var payload = {
              showtimeId: String(st.id),
              seatIds: selected
                .map(function (s) {
                  return s.seatId;
                })
                .join(","),
              payMethod: payMethod,
            };
            if (paymentReference) payload.paymentReference = paymentReference;
            if (cust) payload.customerId = String(cust.id);
            if (vouch && vouch.code) payload.voucherCode = vouch.code;
            if (requestedPoints > 0)
              payload.pointsUsed = String(requestedPoints);
            if (cart.length) {
              payload.concessionLines = cart
                .map(function (it) {
                  return it.productId + ":" + it.qty;
                })
                .join(",");
            }
            hub
              .api("/booking/counter-sale", { method: "POST", body: payload })
              .then(function (resp) {
                // resp có thể là Ticket (khi không F&B) hoặc {ticket, concessionOrder}
                var ticket = resp && resp.ticket ? resp.ticket : resp;
                var msg =
                  "Bán vé thành công: " +
                  (ticket.ticketCode || "#" + ticket.id) +
                  " - Tổng: " +
                  fmtMoney(ticket.totalAmount);
                var codes = [
                  {
                    label: "Mã vé",
                    code: ticket.ticketCode || String(ticket.id || ""),
                  },
                ];
                if (resp && resp.concessionOrder) {
                  var concessionOrder = resp.concessionOrder;
                  msg +=
                    " · Đơn F&B " +
                    (concessionOrder.orderCode || "#" + concessionOrder.id);
                  codes.push({
                    label: "Mã nhận bắp nước / combo",
                    code:
                      concessionOrder.pickupCode || concessionOrder.orderCode,
                  });
                }
                hub.notify(msg, "ok");
                showCounterSaleReceipt(
                  resp && resp.concessionOrder
                    ? "Bán vé và combo thành công"
                    : "Bán vé thành công",
                  codes,
                );
                selectedCart.length = 0;
                customerInfo = null;
                voucherInfo = null;
                voucherCode = "";
                pointsToRedeem = 0;
                if (typeof onSuccess === "function") onSuccess();
              })
              .catch(function (e) {
                hub.notify(e.message || "Không thể bán vé", "err");
              })
              .then(function () {
                sellBtn.disabled = false;
                sellBtn.textContent = "Xác nhận thanh toán & bán vé";
              });
          },
          function () {
            sellBtn.disabled = false;
            sellBtn.textContent = "Xác nhận thanh toán & bán vé";
          },
        );
      });
      body.appendChild(
        el("div", { style: "text-align:right;margin-top:16px" }, [sellBtn]),
      );
    }

    // Voucher system removed; reserved for future membership benefits.

    // Ước lượng tier discount theo tên hạng. Backend LoyaltyService.tierDiscountFor
    // là canonical — giá trị hiển thị ở đây chỉ để staff thấy trước tổng phải trả.
    function estimateTierDiscount(c, amount) {
      if (!c || !amount) return 0;
      if (Number.isFinite(Number(c.tierDiscountPercent))) {
        return Math.floor((amount * Number(c.tierDiscountPercent)) / 100);
      }
      var t = (c.tier || "STANDARD").toUpperCase();
      var pct = 0;
      if (t === "SILVER") pct = 3;
      else if (t === "GOLD") pct = 5;
      else if (t === "PLATINUM") pct = 8;
      else if (t === "DIAMOND") pct = 10;
      return Math.floor((amount * pct) / 100);
    }

    return Promise.resolve();
  }

  // ============================================================
  // POS CONCESSION — POS order + khách + điểm + voucher
  // ============================================================
  function modulePosConcession() {
    var hub = window.CinemaHub;
    var viewRoot = document.getElementById("viewRoot");
    if (!hub || !viewRoot) return Promise.resolve();

    var heading = el("div", { class: "ws-page-heading" }, [
      el("span", { class: "eyebrow" }, ["Concession POS"]),
      el("h1", {}, ["POS - Đồ ăn & thức uống"]),
      el("p", {}, [
        "Chọn sản phẩm/combo, tra cứu khách hàng và xác nhận thu tiền mặt hoặc chuyển khoản tại quầy.",
      ]),
    ]);

    var layout = el("div", { class: "pos-layout" });
    var left = el("div", { class: "pos-left" });
    var right = el("div", { class: "pos-right" });
    layout.appendChild(left);
    layout.appendChild(right);

    var search = el("input", {
      type: "search",
      placeholder: "Tìm sản phẩm...",
      class: "pos-input",
      style: "flex:1;min-width:200px",
    });
    var typeSel = el("select", { class: "pos-input" }, [
      el("option", { value: "" }, ["Tất cả"]),
      el("option", { value: "FOOD" }, ["Đồ ăn"]),
      el("option", { value: "DRINK" }, ["Nước uống"]),
      el("option", { value: "COMBO" }, ["Combo"]),
    ]);
    var filterBar = el("div", { class: "ws-toolbar" }, [search, typeSel]);
    left.appendChild(filterBar);

    var productGrid = el("div", { class: "pos-product-grid" });
    left.appendChild(productGrid);
    productGrid.appendChild(
      el("div", { class: "ws-empty" }, [el("p", {}, ["Đang tải sản phẩm..."])]),
    );

    // Cart
    var cart = [];
    var customerInfo = null;
    var voucherInfo = null;

    var cartBody = el("div", { class: "pos-cart-body" });
    cartBody.appendChild(
      el("p", { class: "muted" }, [
        "Chưa có sản phẩm. Bấm vào sản phẩm bên trái để thêm vào giỏ.",
      ]),
    );
    var cartCard = el("section", { class: "ws-card pos-cart" }, [
      el("h3", { class: "ws-card-title" }, ["Giỏ hàng"]),
      cartBody,
    ]);
    right.appendChild(cartCard);

    var root = el("div", {}, [heading, layout]);
    viewRoot.replaceChildren(root);

    var products = [];
    hub
      .api("/concession/products")
      .then(function (data) {
        products = Array.isArray(data) ? data : [];
        renderProducts();
      })
      .catch(function (e) {
        productGrid.replaceChildren(
          el("div", { class: "ws-empty" }, [
            el("h3", {}, ["Lỗi tải sản phẩm"]),
            el("p", {}, [e.message || ""]),
          ]),
        );
      });

    function renderProducts() {
      var q = (search.value || "").trim().toLowerCase();
      var t = typeSel.value;
      var list = products.filter(function (p) {
        if (t && p.type !== t) return false;
        if (q && (p.name || "").toLowerCase().indexOf(q) < 0) return false;
        return p.status !== "INACTIVE";
      });
      productGrid.replaceChildren();
      if (!list.length) {
        productGrid.appendChild(
          el("div", { class: "ws-empty" }, [
            el("h3", {}, ["Không có sản phẩm"]),
            el("p", {}, ["Thử bỏ lọc hoặc để Admin tạo sản phẩm mới."]),
          ]),
        );
        return;
      }
      list.forEach(function (p) {
        var isCombo = p.type === "COMBO";
        var card = el("button", { class: "pos-product", type: "button" });
        card.innerHTML =
          '<div class="pos-product-name">' +
          escapeHtml(p.name || "-") +
          (isCombo ? ' <span class="pos-combo-tag">COMBO</span>' : "") +
          "</div>" +
          '<div class="pos-product-meta">' +
          escapeHtml(p.unit || (isCombo ? "combo" : "phần")) +
          "</div>" +
          '<div class="pos-product-price">' +
          fmtMoney(p.price) +
          "</div>";
        card.addEventListener("click", function () {
          var ex = cart.find(function (x) {
            return x.productId === p.id;
          });
          if (ex) {
            ex.qty += 1;
          } else {
            cart.push({
              productId: p.id,
              name: p.name,
              unitPrice: p.price,
              qty: 1,
              stock: p.stock,
            });
          }
          renderCart();
        });
        productGrid.appendChild(card);
      });
    }

    function renderCart() {
      cartBody.replaceChildren();
      if (!cart.length) {
        cartBody.appendChild(
          el("p", { class: "muted" }, [
            "Chưa có sản phẩm. Bấm vào sản phẩm bên trái để thêm vào giỏ.",
          ]),
        );
        updateTotal();
        return;
      }

      // Tra cứu khách
      cartBody.appendChild(
        el("h4", { class: "pos-section-title" }, [
          "Khách hàng thành viên (tùy chọn)",
        ]),
      );
      var custEmailInp = el("input", {
        type: "email",
        placeholder: "Email khách",
        class: "pos-input",
      });
      var custPhoneInp = el("input", {
        type: "tel",
        placeholder: "Số điện thoại",
        class: "pos-input",
      });
      var custLookupBtn = el(
        "button",
        { class: "ws-btn secondary small", type: "button" },
        ["Tra cứu"],
      );
      var custBox = el("div", { class: "pos-customer-box" });
      var custError = el("div", {
        class: "ws-field-error",
        "aria-live": "polite",
      });
      if (customerInfo) custBox.appendChild(renderCustomerBadge(customerInfo));

      cartBody.appendChild(
        el("div", { class: "pos-form-grid pos-form-grid-2" }, [
          wrapField("Email", custEmailInp),
          wrapField("Điện thoại", custPhoneInp),
          el("div", { class: "pos-field" }, [
            el("span", {}, [" "]),
            custLookupBtn,
          ]),
        ]),
      );
      cartBody.appendChild(custBox);
      cartBody.appendChild(custError);

      function clearCustomerSelection() {
        customerInfo = null;
        custBox.replaceChildren();
        custError.textContent = "";
      }
      custEmailInp.addEventListener("input", clearCustomerSelection);
      custPhoneInp.addEventListener("input", clearCustomerSelection);

      function validateCustomerLookupInput() {
        var em = custEmailInp.value.trim();
        var ph = custPhoneInp.value.trim();
        if (!em && !ph) {
          custError.textContent =
            "Vui lòng nhập Email hoặc Số điện thoại để tra cứu thành viên.";
          custEmailInp.focus();
          return false;
        }
        if (em) {
          var emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
          if (!emailRegex.test(em)) {
            custError.textContent =
              "Định dạng email không hợp lệ (ví dụ: khachhang@gmail.com).";
            custEmailInp.focus();
            return false;
          }
        }
        if (ph) {
          var cleanPh = ph.replace(/[\s\-\.]/g, "");
          var phoneRegex = /^(0|\+84)[0-9]{9,10}$/;
          if (!phoneRegex.test(cleanPh)) {
            custError.textContent =
              "Số điện thoại không hợp lệ (cần 10 chữ số, ví dụ 0912345678).";
            custPhoneInp.focus();
            return false;
          }
        }
        custError.textContent = "";
        return true;
      }

      custLookupBtn.addEventListener("click", function () {
        if (!validateCustomerLookupInput()) return;
        custLookupBtn.disabled = true;
        custLookupBtn.textContent = "Đang tra...";
        lookupCustomer(
          hub,
          custEmailInp.value,
          custPhoneInp.value,
          function (info, err) {
            custLookupBtn.disabled = false;
            custLookupBtn.textContent = "Tra cứu";
            if (err) {
              clearCustomerSelection();
              custError.textContent = err;
              return;
            }
            customerInfo = info;
            custEmailInp.value = info.email || "";
            custPhoneInp.value = info.phone ? formatPhone(info.phone) : "";
            custError.textContent = "";
            renderCart();
          },
        );
      });

      // Bảng sản phẩm trong giỏ
      var table = el("table", { class: "ws-table" });
      table.innerHTML =
        '<thead><tr><th>Sản phẩm</th><th style="min-width:140px;text-align:center;">Số lượng</th><th>Thành tiền</th><th></th></tr></thead>';
      var tbody = el("tbody");

      cart.forEach(function (it, idx) {
        var tr = document.createElement("tr");
        tr.innerHTML =
          "<td><strong>" +
          escapeHtml(it.name) +
          "</strong>" +
          '<div style="color:#6b7280;font-size:.78rem">' +
          fmtMoney(it.unitPrice) +
          "</div></td>" +
          '<td class="qty-cell" style="text-align:center;white-space:nowrap;">' +
          '<button class="ws-btn small secondary qty-dec" type="button" title="Giảm 1">-</button>' +
          '<input type="number" class="ws-input qty-input" min="1" max="999" step="1" value="' +
          it.qty +
          '" style="width:68px;text-align:center;display:inline-block;margin:0 4px;padding:4px 6px;" aria-label="Số lượng">' +
          '<button class="ws-btn small secondary qty-inc" type="button" title="Tăng 1">+</button>' +
          "</td>" +
          '<td class="line-total-cell" style="font-weight:600;">' +
          fmtMoney(it.unitPrice * it.qty) +
          "</td>" +
          '<td><button class="ws-btn small danger qty-rm" type="button" title="Xóa món">✕</button></td>';

        var qtyInput = tr.querySelector(".qty-input");
        var lineTotal = tr.querySelector(".line-total-cell");

        tr.querySelector(".qty-dec").addEventListener("click", function () {
          if (it.qty <= 1) {
            cart.splice(idx, 1);
            renderCart();
          } else {
            it.qty -= 1;
            qtyInput.value = it.qty;
            lineTotal.textContent = fmtMoney(it.unitPrice * it.qty);
            updateTotal();
          }
        });

        tr.querySelector(".qty-inc").addEventListener("click", function () {
          if (it.qty < 999) {
            it.qty += 1;
            qtyInput.value = it.qty;
            lineTotal.textContent = fmtMoney(it.unitPrice * it.qty);
            updateTotal();
          }
        });

        qtyInput.addEventListener("input", function () {
          var val = parseInt(this.value, 10);
          if (!isNaN(val) && val > 0 && val <= 999) {
            it.qty = val;
            lineTotal.textContent = fmtMoney(it.unitPrice * it.qty);
            updateTotal();
          }
        });

        qtyInput.addEventListener("blur", function () {
          var val = parseInt(this.value, 10);
          if (isNaN(val) || val <= 0) {
            it.qty = 1;
          } else if (val > 999) {
            it.qty = 999;
          } else {
            it.qty = val;
          }
          this.value = it.qty;
          lineTotal.textContent = fmtMoney(it.unitPrice * it.qty);
          updateTotal();
        });

        tr.querySelector(".qty-rm").addEventListener("click", function () {
          cart.splice(idx, 1);
          renderCart();
        });
        tbody.appendChild(tr);
      });
      table.appendChild(tbody);
      cartBody.appendChild(table);

      // Pay method mặc định tiền mặt
      var selectedPayMethod = "CASH";

      // Action buttons
      var action = el("div", { class: "pos-cart-actions" });
      var createBtn = el(
        "button",
        { class: "ws-btn primary", type: "button" },
        ["Tạo đơn POS"],
      );
      var clearBtn = el(
        "button",
        { class: "ws-btn secondary", type: "button" },
        ["Xóa giỏ"],
      );
      action.appendChild(createBtn);
      action.appendChild(clearBtn);
      cartBody.appendChild(action);

      clearBtn.addEventListener("click", function () {
        cart.length = 0;
        voucherInfo = null;
        customerInfo = null;
        renderCart();
      });

      createBtn.addEventListener("click", function () {
        // 1. Kiểm tra giỏ hàng rỗng
        if (!cart || !cart.length) {
          hub.notify(
            "Giỏ hàng trống. Vui lòng chọn ít nhất một món trước khi tạo đơn.",
            "warn",
          );
          return;
        }

        // 2. Kiểm tra số lượng từng món hợp lệ (1 - 999)
        var invalidLine = cart.find(function (item) {
          var q = Number(item.qty);
          var pid = Number(item.productId);
          return (
            !Number.isInteger(pid) ||
            pid <= 0 ||
            !Number.isInteger(q) ||
            q <= 0 ||
            q > 999
          );
        });
        if (invalidLine) {
          hub.notify(
            'Số lượng sản phẩm "' +
              (invalidLine.name || "trong giỏ") +
              '" không hợp lệ (phải từ 1 đến 999).',
            "warn",
          );
          return;
        }

        // 3. Kiểm tra tồn kho trước khi mở dialog thanh toán
        var overStockLine = cart.find(function (item) {
          var prod = products.find(function (p) {
            return p.id === item.productId;
          });
          var available =
            prod && typeof prod.stock === "number"
              ? prod.stock
              : item.stock != null
                ? item.stock
                : null;
          return available != null && item.qty > available;
        });
        if (overStockLine) {
          var prodRef = products.find(function (p) {
            return p.id === overStockLine.productId;
          });
          var avail =
            prodRef && typeof prodRef.stock === "number"
              ? prodRef.stock
              : overStockLine.stock;
          hub.notify(
            'Sản phẩm "' +
              overStockLine.name +
              '" không đủ tồn kho tại chi nhánh (hiện còn ' +
              avail +
              ", yêu cầu " +
              overStockLine.qty +
              "). Vui lòng giảm số lượng!",
            "err",
          );
          return;
        }

        // 4. Kiểm tra đã nhập thông tin thành viên nhưng quên bấm "Tra cứu"
        var hasCustomerLookupInput = !!(
          custEmailInp.value.trim() || custPhoneInp.value.trim()
        );
        if (hasCustomerLookupInput && !customerInfo) {
          custError.textContent =
            "Bạn đã nhập thông tin khách hàng nhưng chưa bấm Tra cứu. Vui lòng bấm Tra cứu hoặc xóa ô nhập nếu là khách vãng lai.";
          custLookupBtn.focus();
          return;
        }

        // 5. Kiểm tra tổng tiền
        var total = cart.reduce(function (sum, item) {
          return sum + Number(item.unitPrice) * Number(item.qty);
        }, 0);
        if (total <= 0) {
          hub.notify("Tổng tiền đơn hàng không hợp lệ.", "warn");
          return;
        }

        createBtn.disabled = true;
        confirmCounterPayment(
          hub,
          selectedPayMethod,
          total,
          "Xác nhận thu tiền cho đơn đồ ăn/thức uống, bao gồm cả combo nếu có?",
          function (paymentReference) {
            createBtn.disabled = true;
            createBtn.textContent = "Đang tạo đơn...";
            var lines = cart
              .map(function (it) {
                return Number(it.productId) + ":" + Number(it.qty);
              })
              .join(",");
            var payload = { lines: lines };
            var cid = customerInfo ? String(customerInfo.id) : null;
            if (cid) payload.customerId = cid;
            hub
              .api("/concession/order", { method: "POST", body: payload })
              .then(function (order) {
                var orderId = order && (order.id || order.orderId);
                if (!orderId) throw new Error("Không lấy được mã đơn");
                var payPayload = {
                  orderId: String(orderId),
                  payMethod: selectedPayMethod,
                };
                if (paymentReference)
                  payPayload.paymentReference = paymentReference;
                if (cid) payPayload.customerId = cid;
                return hub
                  .api("/concession/order/pay", {
                    method: "POST",
                    body: payPayload,
                  })
                  .then(function () {
                    return order;
                  });
              })
              .then(function (order) {
                hub.notify(
                  "Bán thành công đơn #" +
                    order.id +
                    " - Tổng: " +
                    fmtMoney(order.totalAmount || order.total),
                  "ok",
                );
                showCounterSaleReceipt("Bán bắp nước / combo thành công", [
                  {
                    label: "Mã nhận bắp nước / combo",
                    code: order.pickupCode || order.orderCode,
                  },
                ]);

                // Tự động cập nhật tồn kho cục bộ
                cart.forEach(function (cItem) {
                  var prod = products.find(function (p) {
                    return p.id === cItem.productId;
                  });
                  if (prod && typeof prod.stock === "number") {
                    prod.stock = Math.max(0, prod.stock - cItem.qty);
                  }
                });

                cart.length = 0;
                voucherInfo = null;
                customerInfo = null;
                renderCart();
              })
              .catch(function (e) {
                hub.notify(e.message || "Không thể tạo đơn", "err");
              })
              .then(function () {
                createBtn.disabled = false;
                createBtn.textContent = "Tạo đơn POS";
              });
          },
          function () {
            createBtn.disabled = false;
            createBtn.textContent = "Tạo đơn POS";
          },
        );
      });

      updateTotal();
    }

    // Ước lượng tier discount theo tên hạng (chỉ hiển thị tổng kết phía client).
    function estimateTierDiscount(c, amount) {
      if (!c || !amount) return 0;
      var t = (c.tier || "STANDARD").toUpperCase();
      var pct = 0;
      if (t === "SILVER") pct = 3;
      else if (t === "GOLD") pct = 5;
      else if (t === "PLATINUM") pct = 8;
      else if (t === "DIAMOND") pct = 10;
      return Math.floor((amount * pct) / 100);
    }

    function updateTotal() {
      var total = cart.reduce(function (s, it) {
        return s + it.unitPrice * it.qty;
      }, 0);
      var card = cartCard;
      var old = card.querySelector(".pos-cart-total");
      if (old) old.remove();
      var totalEl = el("div", { class: "pos-cart-total" }, [
        el("span", {}, ["Tổng cộng"]),
        el("strong", {}, [fmtMoney(total)]),
      ]);
      card.appendChild(totalEl);
    }

    search.addEventListener("input", renderProducts);
    typeSel.addEventListener("change", renderProducts);
    return Promise.resolve();
  }

  // Expose to window
  window.modulePosTicket = modulePosTicket;
  window.modulePosConcession = modulePosConcession;
})();
