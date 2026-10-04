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
            } else {
                const ctx = canvas.getContext('2d');
                if (!ctx) return;
                canvas.width = canvas.width || 200;
                canvas.height = canvas.height || 60;
                ctx.fillStyle = '#fff';
                ctx.fillRect(0, 0, canvas.width, canvas.height);
                ctx.fillStyle = '#000';
                ctx.font = '16px monospace';
                ctx.fillText(String(value), 10, 30);
            }
        } catch (e) {
            // ignore
        }
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
        if (h1 && title) {
            h1.textContent = title;
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
        showtimeScheduleReminderAction: showtimeScheduleReminderAction,
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
                    const action = showtimeScheduleReminderAction(item);
                    const body = action ? action.body : item.body;
                    const row = el('button', {
                        class: 'notification-item ' + (item.read ? '' : 'unread'),
                        type: 'button'
                    }, [
                        el('strong', {}, [item.title || 'Thông báo']),
                        action ? el('small', {
                            class: 'notification-severity '
                                + (action.urgent ? 'urgent' : 'normal')
                        }, [action.urgent ? 'Khẩn cấp' : 'Cần xếp lịch']) : null,
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
                        }, ['Xếp lịch ngay']);
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

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initNotifications);
    } else {
        initNotifications();
    }
})();
