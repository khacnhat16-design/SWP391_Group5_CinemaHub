/**
 * app.js — Core helpers for CinemaHub UI.
 * Cung cap:
 *   - CinemaHub.api(path, options) -> Promise  (CSRF + JSON parse + envelope error)
 *   - CinemaHub.uploadFile(file, fieldName) -> Promise
 *   - CinemaHub.getSession(force) -> Promise
 *   - CinemaHub.el(tag, attrs, children), .fmtMoney, .fmtDateTime, .notify, .statusBadge, .table, .renderBarcode
 *   - Notification badge + popover (top-right)
 * Tat ca async code chay qua .then()/.catch() chain, KHONG dung async/await.
 */
(function () {
    'use strict';

    const metaCtx = document.querySelector('meta[name="ctx"]');
    const ctx = metaCtx ? metaCtx.content : '';
    const currentRole = (document.querySelector('meta[name="user-role"]') || {}).content || 'GUEST';

    function replaceFailedPoster(img) {
        const fallback = document.createElement('div');
        fallback.className = img.getAttribute('data-poster-fallback') || 'poster-placeholder';
        fallback.textContent = 'NO POSTER';
        img.replaceWith(fallback);
    }

    document.addEventListener('error', function (event) {
        const img = event.target;
        if (img && img.tagName === 'IMG' && img.hasAttribute('data-poster-fallback')) {
            replaceFailedPoster(img);
        }
    }, true);
    document.querySelectorAll('img[data-poster-fallback]').forEach(function (img) {
        if (img.complete && img.naturalWidth === 0) replaceFailedPoster(img);
    });

    let cachedSession = null;

    function getSession(force) {
        if (cachedSession && !force) return Promise.resolve(cachedSession);
        return fetch(ctx + '/api/session', { credentials: 'same-origin' })
            .then(function (res) {
                if (!res.ok) throw new Error('Khong lay duoc phien dang nhap');
                return res.json();
            })
            .then(function (data) {
                cachedSession = data;
                return data;
            });
    }

    function buildHeaders(token, extra) {
        return Object.assign({ 'X-CSRF-Token': token || '' }, extra || {});
    }

    /**
     * Goi API JSON. Tu gan CSRF token cho method doi trang thai.
     * Nem Error voi message tu envelope {code,message} khi server bao loi.
     */
    function api(path, options) {
        const opts = Object.assign({ method: 'GET', credentials: 'same-origin' }, options || {});
        const method = String(opts.method).toUpperCase();
        let p;
        if (method !== 'GET' && method !== 'HEAD') {
            p = getSession().then(function (session) {
                opts.headers = buildHeaders(session.csrfToken, opts.headers);
            });
        } else {
            p = Promise.resolve();
        }
        return p.then(function () {
            // Body handling: if string already passed, just honor caller's content-type.
            // If plain object, serialize to URLSearchParams (Spring binding-friendly).
            // If FormData, pass through.
            if (typeof opts.body === 'string') {
                if (opts.contentType) {
                    opts.headers = Object.assign({ 'Content-Type': opts.contentType }, opts.headers);
                }
            } else if (opts.body && typeof opts.body === 'object' && !(opts.body instanceof FormData)) {
                const form = new URLSearchParams();
                Object.keys(opts.body).forEach(function (k) {
                    const v = opts.body[k];
                    if (v !== null && v !== undefined) form.append(k, v);
                });
                opts.body = form.toString();
                opts.headers = Object.assign({ 'Content-Type': opts.contentType || 'application/x-www-form-urlencoded;charset=UTF-8' }, opts.headers);
            }
            return fetch(ctx + path, opts);
        }).then(function (res) {
            return res.text().then(function (text) {
                let data = null;
                if (text) {
                    try { data = JSON.parse(text); } catch (e) { data = { message: text }; }
                }
                if (!res.ok) {
                    const rawMessage = data && (data.message || (data.error && data.error.message));
                    const msg = rawMessage && !/^\s*</.test(String(rawMessage))
                        ? rawMessage
                        : ('Lỗi máy chủ (' + res.status + '). Vui lòng tải lại trang hoặc khởi động lại ứng dụng.');
                    const err = new Error(msg);
                    err.status = res.status;
                    err.code = data && (data.code || (data.error && data.error.code));
                    err.payload = data;
                    throw err;
                }
                return data;
            });
        });
    }

    // ---------- UI helpers ----------

    const booleanAttributes = new Set([
        'allowfullscreen', 'async', 'autofocus', 'autoplay', 'checked',
        'controls', 'defer', 'disabled', 'formnovalidate', 'hidden',
        'ismap', 'itemscope', 'loop', 'multiple', 'muted', 'novalidate',
        'open', 'readonly', 'required', 'reversed', 'selected'
    ]);

    function el(tag, attrs, children) {
        const node = document.createElement(tag);
        Object.keys(attrs || {}).forEach(function (k) {
            const v = attrs[k];
            if (v == null) return;
            if (k === 'class') node.className = v;
            else if (k === 'html') node.innerHTML = v;
            else if (k.indexOf('on') === 0 && typeof v === 'function') node.addEventListener(k.slice(2), v);
            else if (typeof v === 'boolean' && booleanAttributes.has(k.toLowerCase())) {
                if (v) node.setAttribute(k, '');
                else node.removeAttribute(k);
            }
            else node.setAttribute(k, v);
        });
        appendChildren(node, children);
        return node;
    }

    /**
     * Append children robustly: skip null/undefined, flatten arrays, convert
     * numbers/booleans to text, accept Node or string. Recursive for nested arrays
     * (e.g. when a child render fn returns another array of nodes).
     */
    function appendChildren(parent, children) {
        if (children == null) return;
        if (!Array.isArray(children)) children = [children];
        children.forEach(function (c) {
            if (c == null || c === false) return;
            if (Array.isArray(c)) { appendChildren(parent, c); return; }
            if (typeof c === 'string' || typeof c === 'number') {
                parent.appendChild(document.createTextNode(String(c)));
                return;
            }
            if (c instanceof Node) {
                try {
                    parent.appendChild(c);
                } catch (err) {
                    throw err;
                }
                return;
            }
            // Fallback: stringify để tránh TypeError khi developer nhầm type.
            try { parent.appendChild(document.createTextNode(String(c))); } catch (_) {}
        });
    }

    function fmtMoney(value) {
        if (value == null || isNaN(value)) return '0 d';
        return Number(value).toLocaleString('vi-VN') + ' d';
    }

    function fmtDateTime(value) {
        if (!value) return '';
        try {
            const d = new Date(value);
            if (isNaN(d.getTime())) return String(value);
            const pad = function (n) { return n < 10 ? '0' + n : '' + n; };
            return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ' ' + pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear();
        } catch (e) {
            return String(value);
        }
    }

    function repairMojibakeText(value) {
        const text = String(value || '');
        if (!/(?:Ã.|Â.|Ä.|á»|áº|â€)/.test(text)
                || typeof TextDecoder !== 'function' || typeof TextEncoder !== 'function') {
            return text;
        }
        const cp1252 = '\u20AC\u0081\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030'
            + '\u0160\u2039\u0152\u008D\u017D\u008F\u0090\u2018\u2019\u201C\u201D'
            + '\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u009D\u017E\u0178';
        const bytes = [];
        const encoder = new TextEncoder();
        for (const character of text) {
            const code = character.charCodeAt(0);
            if (code <= 0x7F || (code >= 0xA0 && code <= 0xFF)) {
                bytes.push(code);
            } else {
                const index = cp1252.indexOf(character);
                if (index >= 0) bytes.push(index + 0x80);
                else bytes.push(...encoder.encode(character));
            }
        }
        try {
            const repaired = new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array(bytes));
            return repaired;
        } catch (e) {
            return text;
        }
    }

    function fmtNotificationDateTime(value) {
        if (!value) return '';
        try {
            const raw = String(value);
            const hasTimezone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(raw);
            const d = new Date(hasTimezone ? raw : raw + 'Z');
            if (isNaN(d.getTime())) return raw;
            return d.toLocaleString('vi-VN', {
                timeZone: 'Asia/Ho_Chi_Minh',
                hour: '2-digit',
                minute: '2-digit',
                day: '2-digit',
                month: '2-digit',
                year: 'numeric',
                hour12: false
            }).replace(',', '');
        } catch (e) {
            return String(value);
        }
    }

    function notify(message, type) {
        if (window.notifyHub) return window.notifyHub(message, type);
        // Fallback: nếu page chưa nạp notifyHub (vd. embed iframe), tự dựng
        // toast inline để feedback vẫn hiển thị — quan trọng cho trang login.
        let box = document.getElementById('hubNotifyBox');
        if (!box) {
            box = el('div', { id: 'hubNotifyBox', class: 'hub-notify-box' });
            document.body.appendChild(box);
        }
        const item = el('div', { class: 'hub-notify-item hub-notify-' + (type || 'info') }, [String(message || '')]);
        box.appendChild(item);
        setTimeout(function () { item.classList.add('show'); }, 10);
        setTimeout(function () {
            item.classList.remove('show');
            setTimeout(function () { item.remove(); }, 300);
        }, 3500);
    }

    function statusBadge(label) {
        const map = {
            PAID: 'green', CONFIRMED: 'green', USED: 'purple', SELLING: 'blue',
            OPEN: 'blue', SOLD_OUT: 'red', ALMOST_FULL: 'orange', PENDING: 'orange',
            CANCELLED: 'red', ENDED: 'gray', INACTIVE: 'red', ACTIVE: 'green',
            EXPIRED: 'gray', HOLD: 'orange', FAILED: 'red'
        };
        const tone = map[label] || 'gray';
        return '<span class="ws-badge ws-status-pill ws-badge-' + tone + '">' + String(label || '') + '</span>';
    }

    function table(columns, rows) {
        const head = el('tr', {}, columns.map(function (c) {
            return el('th', {}, [c.label || '']);
        }));
        const body = (rows && rows.length)
            ? rows.map(function (r) {
                return el('tr', {}, columns.map(function (c) {
                    return el('td', {}, [c.render ? c.render(r) : String(r[c.key] != null ? r[c.key] : '')]);
                }));
            })
            : [el('tr', {}, [el('td', { colspan: String(columns.length), class: 'muted' }, ['Khong co du lieu'])])];
        return el('div', { class: 'table-wrap' }, [el('table', {}, [el('thead', {}, [head]), el('tbody', {}, body)])]);
    }

    function renderBarcode(canvas, value) {
        if (!canvas || !value) return;
        try {
            if (window.JsBarcode) {
                window.JsBarcode(canvas, value, { format: 'CODE128', displayValue: false, height: 60 });
                return;
            }
        } catch (_) { }

        // Codes issued by CinemaHub use the Code 39 character set, so keep them
        // scannable even when the optional CODE128 renderer is not available.
        const code39 = {
            '0': '000110100', '1': '100100001', '2': '001100001', '3': '101100000',
            '4': '000110001', '5': '100110000', '6': '001110000', '7': '000100101',
            '8': '100100100', '9': '001100100', 'A': '100001001', 'B': '001001001',
            'C': '101001000', 'D': '000011001', 'E': '100011000', 'F': '001011000',
            'G': '000001101', 'H': '100001100', 'I': '001001100', 'J': '000011100',
            'K': '100000011', 'L': '001000011', 'M': '101000010', 'N': '000010011',
            'O': '100010010', 'P': '001010010', 'Q': '000000111', 'R': '100000110',
            'S': '001000110', 'T': '000010110', 'U': '110000001', 'V': '011000001',
            'W': '111000000', 'X': '010010001', 'Y': '110010000', 'Z': '011010000',
            '-': '010000101', '.': '110000100', ' ': '011000100', '$': '010101000',
            '/': '010100010', '+': '010001010', '%': '000101010', '*': '010010100'
        };
        const encoded = '*' + String(value).toUpperCase() + '*';
        if ([...encoded].some(function (character) { return !code39[character]; })) {
            canvas.hidden = true;
            return;
        }
        const narrow = 2;
        const wide = narrow * 3;
        const quiet = narrow * 10;
        let units = quiet * 2 / narrow + (encoded.length - 1);
        [...encoded].forEach(function (character) {
            [...code39[character]].forEach(function (width) {
                units += width === '1' ? 3 : 1;
            });
        });
        canvas.width = units * narrow;
        canvas.height = 64;
        canvas.hidden = false;
        canvas.setAttribute('aria-label', 'Mã vạch ' + value);
        const context = canvas.getContext('2d');
        if (!context) {
            canvas.hidden = true;
            return;
        }
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = '#111';
        let x = quiet;
        [...encoded].forEach(function (character, characterIndex) {
            [...code39[character]].forEach(function (width, index) {
                const barWidth = width === '1' ? wide : narrow;
                if (index % 2 === 0) context.fillRect(x, 2, barWidth, canvas.height - 4);
                x += barWidth;
            });
            if (characterIndex < encoded.length - 1) x += narrow;
        });
    }

    // ---------- Notification badge ----------

    function refreshNotificationBadge() {
        const badge = document.getElementById('notifCount');
        if (!badge) return Promise.resolve();
        return api('/notification').then(function (data) {
            const count = Number(data && data.unreadCount) || 0;
            const bell = document.getElementById('notificationBell');
            const previous = Number(badge.textContent || 0);
            if (count > 0) {
                badge.textContent = String(count);
                badge.hidden = false;
                if (bell && count > previous) {
                    bell.classList.remove('notification-pulse');
                    void bell.offsetWidth;
                    bell.classList.add('notification-pulse');
                }
            } else {
                badge.hidden = true;
            }
        });
    }

    function showtimeScheduleReminderAction(item) {
        const reminderType = item && item.type;
        if (reminderType !== 'SHOWTIME_SCHEDULE_REMINDER'
                && reminderType !== 'SHOWTIME_SCHEDULE_URGENT') return null;
        const body = String(item.body || '');
        const actionPattern =
            /\s*·\s*\/console\?module=showtime-allocation&movieId=(\d+)&branchId=(\d+)\s*$/;
        const match = actionPattern.exec(body);
        if (!match) return null;
        const query = new URLSearchParams({
            module: 'showtime-allocation',
            movieId: match[1],
            branchId: match[2]
        });
        return {
            href: ctx + '/console?' + query.toString(),
            body: body.slice(0, match.index).trim(),
            urgent: reminderType === 'SHOWTIME_SCHEDULE_URGENT'
        };
    }

    function refundRequestAction(item) {
        if (!item || item.type !== 'REFUND_REQUEST') return null;
        return {
            href: ctx + '/console?module=refunds&status=PENDING',
            label: 'Xem yêu cầu hoàn tiền',
            body: String(item.body || '')
        };
    }

    function inventoryAlertAction(item) {
        if (!item) return null;
        if (item.type === 'INVENTORY_LOW_STOCK' || item.type === 'INVENTORY_EXPIRY_WARNING') {
            const titleLower = (item.title || '').toLowerCase();
            const bodyLower = (item.body || '').toLowerCase();
            const isMaterial = titleLower.indexOf('nguyên liệu') !== -1 || bodyLower.indexOf('nguyên liệu') !== -1;
            const isOutOfStock = titleLower.indexOf('hết hàng') !== -1 || bodyLower.indexOf('hết hàng') !== -1;
            const isExpired = titleLower.indexOf('hết hạn') !== -1 || bodyLower.indexOf('hết hạn') !== -1;
            const isExpiringSoon = titleLower.indexOf('cận hạn') !== -1 || bodyLower.indexOf('cận hạn') !== -1;

            let severityLabel = 'Sắp hết';
            if (isOutOfStock) severityLabel = 'Hết hàng';
            else if (isExpired) severityLabel = 'Hết hạn';
            else if (isExpiringSoon) severityLabel = 'Cận hạn';

            return {
                href: ctx + '/console?module=inventory&tab=' + (isMaterial ? 'material' : 'product'),
                label: 'Xem kho hàng',
                body: String(item.body || ''),
                urgent: isOutOfStock || isExpired,
                severityLabel: severityLabel
            };
        }
        return null;
    }

    function uploadFile(file, fieldName) {
        const formData = new FormData();
        formData.append(fieldName || 'file', file);
        return getSession().then(function (session) {
            const headers = buildHeaders(session.csrfToken);
            return fetch(ctx + '/upload', {
                method: 'POST',
                credentials: 'same-origin',
                headers: headers,
                body: formData
            });
        }).then(function (res) {
            return res.text().then(function (text) {
                let data = null;
                if (text) {
                    try { data = JSON.parse(text); } catch (e) { data = { message: text }; }
                }
                if (!res.ok) {
                    const msg = (data && (data.message || (data.error && data.error.message))) || ('Upload that bai ' + res.status);
                    const err = new Error(msg);
                    err.status = res.status;
                    err.code = data && (data.code || (data.error && data.error.code));
                    throw err;
                }
                return data;
            });
        });
    }

    function setWorkspaceHeader(title) {
        const h1 = document.querySelector('.ws-header h1');
        const eyebrow = document.querySelector('.ws-header .eyebrow');
        if (h1) {
            // Nếu title rỗng/null thì giữ nguyên giá trị từ JSP (mặc định 'Bảng điều khiển')
            // — chỉ cập nhật khi có title cụ thể (vd. 'Thêm phim mới', 'Danh mục phim').
            if (title) h1.textContent = title;
        }
        if (eyebrow) {
            eyebrow.textContent = 'KHU VỰC QUẢN LÝ CINEMAHUB';
        }
        if (title) document.title = title + ' | Khu vực quản lý CinemaHub';
    }

    window.CinemaHub = {
        ctx: ctx,
        api: api,
        uploadFile: uploadFile,
        getSession: getSession,
        el: el,
        fmtMoney: fmtMoney,
        fmtDateTime: fmtDateTime,
        fmtNotificationDateTime: fmtNotificationDateTime,
        repairMojibakeText: repairMojibakeText,
        showtimeScheduleReminderAction: showtimeScheduleReminderAction,
        refundRequestAction: refundRequestAction,
        notify: notify,
        statusBadge: statusBadge,
        table: table,
        renderBarcode: renderBarcode,
        setWorkspaceHeader: setWorkspaceHeader,
        refreshNotifications: function () {
            return refreshNotificationBadge().catch(function () {
                return new Promise(function (resolve) {
                    setTimeout(function () {
                        refreshNotificationBadge().then(resolve).catch(resolve);
                    }, 250);
                });
            });
        },
        currentRole: currentRole
    };

    // ---------- Init ----------
    function initNotifications() {
        if (currentRole === 'GUEST') return;

        refreshNotificationBadge().catch(function (error) {
            console.error('Không thể tải số lượng thông báo chưa đọc.', error);
        });

        // Tự động kiểm tra thông báo định kỳ mỗi 15 giây
        setInterval(function () {
            refreshNotificationBadge().catch(function () {});
        }, 15000);

        const bell = document.getElementById('notificationBell');
        const popover = document.getElementById('notificationPopover');
        if (!bell || !popover) return;

        bell.addEventListener('click', function () {
            const opening = popover.hidden;
            popover.hidden = !opening;
            bell.setAttribute('aria-expanded', String(opening));
            if (!opening) return;
            popover.replaceChildren(el('div', { class: 'notification-loading' }, ['Đang tải thông báo...']));
            api('/notification').then(function (data) {
                const list = (data && data.notifications) || [];
                const markAll = el('button', {
                    class: 'notification-mark-all',
                    type: 'button'
                }, ['Đọc tất cả']);
                const head = el('div', { class: 'notification-popover-head' }, [
                    el('strong', {}, ['Thông báo']),
                    el('a', {
                        class: 'notification-view-all',
                        href: ctx + '/console?module=notification'
                    }, ['Xem tất cả']),
                    markAll
                ]);
                markAll.addEventListener('click', function () {
                    api('/notification/read-all', { method: 'POST' }).then(function () {
                        return refreshNotificationBadge();
                    }).then(function () {
                        popover.hidden = true;
                    }).catch(function (e) { notify(e.message || 'Loi', 'err'); });
                });
                popover.replaceChildren(head);
                if (!list.length) {
                    popover.appendChild(el('p', { class: 'notification-empty' }, ['Chưa có thông báo.']));
                    return;
                }
                list.slice(0, 6).forEach(function (item) {
                    const action = showtimeScheduleReminderAction(item) || refundRequestAction(item) || inventoryAlertAction(item);
                    const body = repairMojibakeText(action ? action.body : item.body);
                    const row = el('button', {
                        class: 'notification-item ' + (item.read ? '' : 'unread'),
                        type: 'button'
                    }, [
                        el('strong', {}, [repairMojibakeText(item.title || 'Thông báo')]),
                        action && action.urgent !== undefined ? el('small', {
                            class: 'notification-severity '
                                + (action.urgent ? 'urgent' : 'normal')
                        }, [action.severityLabel || (action.urgent ? 'Khẩn cấp' : 'Cần xử lý')]) : null,
                        body ? el('span', {}, [body]) : null,
                        el('small', {}, [fmtNotificationDateTime(item.createdAt)])
                    ].filter(Boolean));
                    row.addEventListener('click', function () {
                        const chain = (!item.read
                            ? api('/notification/' + item.id + '/read', { method: 'POST' })
                            : Promise.resolve()
                        ).then(function () { return refreshNotificationBadge(); });
                        chain.then(function () {
                            row.classList.remove('unread');
                        }).catch(function (e) { notify(e.message || 'Không thể cập nhật thông báo.', 'err'); });
                    });
                    const entry = el('div', { class: 'notification-entry' }, [row]);
                    if (action) {
                        const link = el('a', {
                            class: 'notification-action',
                            href: action.href
                        }, [action.label || 'Xem chi tiết']);
                        link.addEventListener('click', function (event) {
                            event.preventDefault();
                            const markRead = !item.read
                                ? api('/notification/' + item.id + '/read', { method: 'POST' })
                                : Promise.resolve();
                            markRead.then(function () {
                                return refreshNotificationBadge();
                            }).then(function () {
                                window.location.assign(action.href);
                            }).catch(function (e) {
                                notify(e.message || 'Không thể mở phân bổ suất chiếu.', 'err');
                            });
                        });
                        entry.appendChild(link);
                    }
                    popover.appendChild(entry);
                });
            }).catch(function (error) {
                popover.replaceChildren(el('div', { class: 'notification-error' },
                    [error.message || 'Không thể tải thông báo.']));
            });
        });
    }

    function initCustomerHeader() {
        if (currentRole !== 'CUSTOMER' || !document.getElementById('customerPoints')) return;
        api('/api/profile').then(function (data) {
            const points = document.getElementById('customerPoints');
            if (!data || !data.profile || !points) {
                throw new Error('Dữ liệu điểm tài khoản không hợp lệ.');
            }
            points.textContent = Number(data.profile.points || 0).toLocaleString('vi-VN');
        }).catch(function (error) {
            console.error('Không thể tải điểm trên header khách hàng.', error);
            const points = document.getElementById('customerPoints');
            if (points) points.textContent = '—';
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            initNotifications();
            initCustomerHeader();
        });
    } else {
        initNotifications();
        initCustomerHeader();
    }
})();
