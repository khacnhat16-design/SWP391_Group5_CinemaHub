/**
 * modules-rsc.js — Reviews / Support / Complaints renderers for ?module=.
 *
 * Hooks:
 *   CinemaHubPages.support      (Customer)
 *   CinemaHubPages['support-inbox'] (Manager)
 *   CinemaHubPages.complaints  (Customer own list + Manager inbox based on role)
 *
 * Backed by REST:
 *   /api/reviews?movieId=
 *   /api/support-requests/mine
 *   /api/support-requests       (Manager)
 *   /api/support-requests/{id}/reply
 *   /api/support-requests/{id}/close
 *   /api/complaints/mine
 *   /api/complaints              (Manager)
 *   /api/complaints/{id}/reply
 *   /api/complaints/{id}/status
 */(function () {
    'use strict';

    var hub = window.CinemaHub;
    var el = hub && hub.el ? hub.el : function (tag, attrs, children) {
        var n = document.createElement(tag);
        if (attrs) Object.keys(attrs).forEach(function (k) {
            if (k === 'class') n.className = attrs[k];
            else if (k === 'html') n.innerHTML = attrs[k];
            else if (k.startsWith('on')) n.addEventListener(k.substring(2), attrs[k]);
            else n.setAttribute(k, attrs[k]);
        });
        if (children) children.forEach(function (c) {
            if (c == null) return;
            try { n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); }
            catch (err) {
                throw err;
            }
        });
        return n;
    };
    var ctx = document.querySelector('meta[name="ctx"]')?.content || '';
    var csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';
    var sessionPromise = null;

    function getSessionForCsrf() {
        if (sessionPromise) return sessionPromise;
        if (csrfToken) {
            sessionPromise = Promise.resolve({ csrfToken: csrfToken });
            return sessionPromise;
        }
        sessionPromise = fetch(ctx + '/api/session', { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : { csrfToken: '' }; })
            .catch(function () { return { csrfToken: '' }; });
        return sessionPromise;
    }

    function api(path, opts) {
        opts = opts || {};
        var method = String(opts.method || 'GET').toUpperCase();
        var headers = { 'Accept': 'application/json' };
        var body = null;
        if (opts.body) {
            headers['Content-Type'] = 'application/x-www-form-urlencoded';
            body = Object.keys(opts.body)
                .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(opts.body[k]); })
                .join('&');
        }
        var needsCsrf = method !== 'GET' && method !== 'HEAD';
        var proceed = needsCsrf
            ? getSessionForCsrf().then(function (s) {
                if (s && s.csrfToken) headers['X-CSRF-Token'] = s.csrfToken;
            })
            : Promise.resolve();
        return proceed.then(function () {
            return fetch(ctx + path, {
                method: method,
                headers: headers,
                body: body,
                credentials: 'same-origin'
            });
        }).then(function (r) {
            return r.text().then(function (text) {
                var json = null;
                if (text) {
                    try { json = JSON.parse(text); } catch (e) { json = null; }
                }
                return { status: r.status, data: json, text: text };
            });
        });
    }

    function statusBadge(s) {
        var map = {
            'OPEN': 'badge-open', 'IN_PROGRESS': 'badge-progress',
            'ANSWERED': 'badge-success', 'CLOSED': 'badge-muted',
            'NEW': 'badge-new', 'RESOLVED': 'badge-success', 'REJECTED': 'badge-danger'
        };
        var labels = {
            'OPEN': 'Mới tiếp nhận', 'IN_PROGRESS': 'Đang xử lý',
            'ANSWERED': 'Đã phản hồi', 'CLOSED': 'Đã đóng',
            'NEW': 'Mới tiếp nhận', 'RESOLVED': 'Đã giải quyết', 'REJECTED': 'Đã từ chối'
        };
        var cls = map[s] || 'badge-muted';
        return el('span', { class: 'ws-badge ' + cls }, [labels[s] || s]);
    }

    function starString(rating) {
        var full = Math.floor(rating);
        var half = (rating - full) >= 0.5 ? 1 : 0;
        var empty = 5 - full - half;
        return '★'.repeat(full) + (half ? '½' : '') + '☆'.repeat(empty);
    }

    function formatDate(d) {
        if (!d) return '';
        return d.replace('T', ' ').substring(0, 16);
    }

    function categoryTag(cat) {
        var labels = {
            'STAFF': 'Nhân viên tại rạp',
            'FACILITY': 'Cơ sở vật chất tại rạp',
            'PAYMENT': 'Thanh toán tại rạp',
            'OTHER': 'Dịch vụ khác tại rạp'
        };
        return el('span', { class: 'ws-category-tag' }, [labels[cat] || cat || '']);
    }

    // Interactive 5-star rating widget. onChange(rating) fires when user picks.
    function starPicker(name, initial, onChange) {
        var value = initial || 0;
        var host = el('div', { class: 'ws-star-rating', role: 'radiogroup', 'aria-label': 'Chọn số sao' });
        var inputs = [];
        for (var i = 1; i <= 5; i++) {
            (function (n) {
                var star = el('span', {
                    class: 'ws-star ' + (n <= value ? 'is-active' : 'is-empty'),
                    role: 'radio',
                    'aria-checked': n === value ? 'true' : 'false',
                    tabindex: '0',
                    'data-value': n
                }, [n <= value ? '★' : '☆']);
                star.addEventListener('click', function () {
                    value = n;
                    refresh();
                    if (onChange) onChange(n);
                });
                star.addEventListener('keydown', function (e) {
                    if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        value = n;
                        refresh();
                        if (onChange) onChange(n);
                    }
                });
                host.appendChild(star);
                inputs.push(star);
            })(i);
        }
        var hidden = el('input', { type: 'hidden', name: name, value: String(value) });
        host.appendChild(hidden);
        function refresh() {
            inputs.forEach(function (s, idx) {
                var n = idx + 1;
                s.className = 'ws-star ' + (n <= value ? 'is-active' : 'is-empty');
                s.firstChild.nodeValue = n <= value ? '★' : '☆';
                s.setAttribute('aria-checked', n === value ? 'true' : 'false');
            });
            hidden.value = String(value);
        }
        host.setValue = function (v) { value = v; refresh(); };
        host.getValue = function () { return value; };
        return host;
    }

    // ======================== SUPPORT (Customer) ========================
    function moduleSupport() {
        var view = el('div', { class: 'ws-page' }, [
            el('div', { class: 'ws-page-header' }, [
                el('div', {}, [
                    el('span', { class: 'ws-eyebrow' }, ['YÊU CẦU HỖ TRỢ']),
                    el('h2', {}, ['Gửi yêu cầu hỗ trợ']),
                    el('p', { class: 'ws-muted' }, ['Đặt câu hỏi hoặc yêu cầu hỗ trợ. Manager sẽ phản hồi qua trang này.'])
                ])
            ])
        ]);
        var listContainer = el('div', { id: 'support-list', class: 'ws-list' });

        // Form
        var subjectInput = el('input', { type: 'text', class: 'ws-input', placeholder: 'Tiêu đề (tối đa 150 ký tự)', maxlength: '150' });
        var messageInput = el('textarea', { class: 'ws-textarea', rows: '4', placeholder: 'Mô tả chi tiết (tối đa 2000 ký tự)', maxlength: '2000' });
        var submitBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Gửi yêu cầu']);
        var msg = el('div', { class: 'ws-flash', hidden: true });

        submitBtn.addEventListener('click', function () {
            var subject = subjectInput.value.trim();
            var message = messageInput.value.trim();
            if (!subject || !message) {
                msg.hidden = false;
                msg.textContent = 'Vui lòng nhập đầy đủ tiêu đề và nội dung';
                msg.className = 'ws-flash err';
                return;
            }
            api('/api/support-requests', {
                method: 'POST',
                body: { subject: subject, message: message }
            }).then(function (r) {
                if (r.status === 200 && r.data) {
                    msg.hidden = false;
                    msg.textContent = 'Đã gửi yêu cầu. Mã #' + r.data.id;
                    msg.className = 'ws-flash ok';
                    subjectInput.value = '';
                    messageInput.value = '';
                    load();
                } else {
                    msg.hidden = false;
                    msg.textContent = (r.data && r.data.message) || 'Gửi thất bại';
                    msg.className = 'ws-flash err';
                }
            });
        });

        var formCard = el('section', { class: 'ws-card' }, [
            el('h3', {}, ['Tạo yêu cầu mới']),
            el('label', { class: 'ws-label' }, ['Tiêu đề', subjectInput]),
            el('label', { class: 'ws-label' }, ['Nội dung', messageInput]),
            el('div', { class: 'ws-actions' }, [submitBtn]),
            msg
        ]);

        function load() {
            api('/api/support-requests/mine').then(function (r) {
                listContainer.replaceChildren();
                if (r.status !== 200 || !r.data || !r.data.items || r.data.items.length === 0) {
                    listContainer.appendChild(el('p', { class: 'ws-muted' }, ['Bạn chưa có yêu cầu hỗ trợ nào.']));
                    return;
                }
                r.data.items.forEach(function (it) {
                    var card = el('article', { class: 'ws-card' }, [
                        el('div', { class: 'ws-card-header' }, [
                            el('strong', {}, [it.subject]),
                            statusBadge(it.status)
                        ]),
                        el('div', { class: 'ws-muted small' }, ['Gửi lúc: ' + formatDate(it.createdAt)]),
                        el('p', {}, [it.message]),
                        it.managerReply ? el('div', { class: 'ws-callout' }, [
                            el('strong', {}, ['Phản hồi từ Manager']),
                            el('p', {}, [it.managerReply]),
                            el('small', { class: 'ws-muted' }, [formatDate(it.repliedAt)])
                        ]) : null
                    ]);
                    listContainer.appendChild(card);
                });
            });
        }

        view.appendChild(formCard);
        view.appendChild(el('h3', {}, ['Yêu cầu của tôi']));
        view.appendChild(listContainer);
        load();
        return view;
    }

    // ======================== SUPPORT INBOX (Manager) ========================
    function moduleSupportInbox() {
        var view = el('div', { class: 'ws-page' }, [
            el('div', { class: 'ws-page-header' }, [
                el('span', { class: 'ws-eyebrow' }, ['QUẢN LÝ HỖ TRỢ']),
                el('h2', {}, ['Hộp thư hỗ trợ'])
            ])
        ]);
        var listContainer = el('div', { id: 'support-inbox-list' });

        var filterSelect = el('select', { class: 'ws-input' }, [
            el('option', { value: '' }, ['Tất cả']),
            el('option', { value: 'OPEN' }, ['OPEN']),
            el('option', { value: 'IN_PROGRESS' }, ['IN_PROGRESS']),
            el('option', { value: 'ANSWERED' }, ['ANSWERED']),
            el('option', { value: 'CLOSED' }, ['CLOSED'])
        ]);
        filterSelect.addEventListener('change', load);

        function load() {
            var status = filterSelect.value;
            var qs = status ? ('?status=' + encodeURIComponent(status)) : '';
            api('/api/support-requests' + qs).then(function (r) {
                listContainer.replaceChildren();
                if (r.status !== 200 || !r.data || !r.data.items) {
                    listContainer.appendChild(el('p', { class: 'ws-muted' }, ['Không có yêu cầu nào.']));
                    return;
                }
                r.data.items.forEach(function (it) {
                    var replyInput = el('textarea', { class: 'ws-textarea', rows: '2', placeholder: 'Phản hồi...', maxlength: '1000' });
                    var replyBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Gửi phản hồi']);
                    var closeBtn = el('button', { class: 'ws-btn ghost', type: 'button' }, ['Đóng yêu cầu']);
                    var flash = el('div', { class: 'ws-flash', hidden: true });

                    replyBtn.addEventListener('click', function () {
                        var reply = replyInput.value.trim();
                        if (!reply) {
                            flash.hidden = false; flash.textContent = 'Nhập nội dung phản hồi';
                            flash.className = 'ws-flash err'; return;
                        }
                        api('/api/support-requests/' + it.id + '/reply', { method: 'POST', body: { reply: reply } })
                            .then(function (res) {
                                flash.hidden = false;
                                flash.textContent = res.status === 200 ? 'Đã phản hồi.' : ((res.data && res.data.message) || 'Lỗi');
                                flash.className = 'ws-flash ' + (res.status === 200 ? 'ok' : 'err');
                                if (res.status === 200) load();
                            });
                    });
                    closeBtn.addEventListener('click', function () {
                        api('/api/support-requests/' + it.id + '/close', { method: 'POST' })
                            .then(function (res) {
                                flash.hidden = false;
                                flash.textContent = res.status === 200 ? 'Đã đóng.' : ((res.data && res.data.message) || 'Lỗi');
                                flash.className = 'ws-flash ' + (res.status === 200 ? 'ok' : 'err');
                                if (res.status === 200) load();
                            });
                    });

                    var card = el('article', { class: 'ws-card' }, [
                        el('div', { class: 'ws-card-header' }, [
                            el('strong', {}, [it.subject]),
                            statusBadge(it.status)
                        ]),
                        el('div', { class: 'ws-muted small' }, [
                            'Từ: ' + it.userFullName + ' (' + it.userEmail + ') · ',
                            formatDate(it.createdAt)
                        ]),
                        el('p', {}, [it.message]),
                        it.managerReply ? el('div', { class: 'ws-callout' }, [
                            el('strong', {}, ['Phản hồi hiện tại']),
                            el('p', {}, [it.managerReply])
                        ]) : null,
                        el('div', { class: 'ws-form-row' }, [
                            el('label', {}, ['Phản hồi cho khách hàng', replyInput])
                        ]),
                        el('div', { class: 'ws-actions' }, [replyBtn, closeBtn]),
                        flash
                    ]);
                    listContainer.appendChild(card);
                });
            });
        }

        view.appendChild(el('div', { class: 'ws-row' }, [
            el('label', { class: 'ws-label' }, ['Trạng thái', filterSelect])
        ]));
        view.appendChild(listContainer);
        load();
        return view;
    }

    // ======================== COMPLAINTS ========================
    function moduleComplaints() {
        var role = document.querySelector('meta[name="user-role"]')?.content || 'CUSTOMER';
        var isManager = (role === 'ADMIN' || role === 'BRANCH_MANAGER');
        return isManager ? moduleComplaintsManager() : moduleComplaintsCustomer();
    }

    function moduleComplaintsCustomer() {
        var view = el('div', { class: 'ws-page' }, [
            el('div', { class: 'ws-page-header' }, [
                el('span', { class: 'ws-eyebrow' }, ['KHIẾU NẠI VỀ RẠP']),
                el('h2', {}, ['Phản ánh chất lượng dịch vụ tại rạp']),
                el('p', { class: 'ws-muted' }, [
                    'Gửi phản ánh về chi nhánh, nhân viên, cơ sở vật chất hoặc dịch vụ. Nội dung đánh giá phim thuộc mục Đánh giá chất lượng.'
                ])
            ])
        ]);
        var listContainer = el('div', { id: 'complaint-list' });

        var branchSelect = el('select', { class: 'ws-input', required: 'required' }, [
            el('option', { value: '' }, ['— Chọn rạp/chi nhánh xảy ra sự việc —'])
        ]);
        var categorySelect = el('select', { class: 'ws-input' }, [
            el('option', { value: 'STAFF' }, ['Nhân viên tại rạp']),
            el('option', { value: 'FACILITY' }, ['Cơ sở vật chất tại rạp']),
            el('option', { value: 'PAYMENT' }, ['Thanh toán tại rạp']),
            el('option', { value: 'OTHER' }, ['Chất lượng dịch vụ khác tại rạp'])
        ]);
        var subjectInput = el('input', { type: 'text', class: 'ws-input', placeholder: 'Tiêu đề', maxlength: '150' });
        var messageInput = el('textarea', { class: 'ws-textarea', rows: '4', placeholder: 'Mô tả chi tiết', maxlength: '2000' });
        var ticketInput = el('input', { type: 'number', class: 'ws-input', placeholder: 'ID vé liên quan (tuỳ chọn)' });
        var submitBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Gửi khiếu nại']);
        submitBtn.disabled = true;
        var msg = el('div', { class: 'ws-flash', hidden: true });

        submitBtn.addEventListener('click', function () {
            var body = {
                branchId: branchSelect.value,
                category: categorySelect.value,
                subject: subjectInput.value.trim(),
                message: messageInput.value.trim(),
                relatedTicketId: ticketInput.value.trim() || ''
            };
            if (!body.branchId) {
                msg.hidden = false; msg.textContent = 'Vui lòng chọn rạp/chi nhánh nơi xảy ra sự việc'; msg.className = 'ws-flash err'; return;
            }
            if (!body.subject || !body.message) {
                msg.hidden = false; msg.textContent = 'Nhập tiêu đề và nội dung'; msg.className = 'ws-flash err'; return;
            }
            api('/api/complaints', { method: 'POST', body: body }).then(function (r) {
                msg.hidden = false;
                msg.textContent = r.status === 200 ? ('Đã gửi khiếu nại #' + r.data.id) : ((r.data && r.data.message) || 'Lỗi');
                msg.className = 'ws-flash ' + (r.status === 200 ? 'ok' : 'err');
                if (r.status === 200) { subjectInput.value = ''; messageInput.value = ''; ticketInput.value = ''; load(); }
            }).catch(function (error) {
                msg.hidden = false;
                msg.textContent = error.message || 'Không gửi được khiếu nại. Vui lòng thử lại.';
                msg.className = 'ws-flash err';
            });
        });

        api('/branch').then(function (r) {
            if (r.status !== 200 || !Array.isArray(r.data)) {
                throw new Error((r.data && r.data.message) || 'Không tải được danh sách chi nhánh.');
            }
            r.data.forEach(function (branch) {
                branchSelect.appendChild(el('option', { value: String(branch.id) }, [branch.name || ('Chi nhánh #' + branch.id)]));
            });
            submitBtn.disabled = r.data.length === 0;
            if (!r.data.length) {
                msg.hidden = false;
                msg.textContent = 'Hiện không có chi nhánh đang hoạt động để gửi khiếu nại.';
                msg.className = 'ws-flash err';
            }
        }).catch(function (error) {
            msg.hidden = false;
            msg.textContent = error.message || 'Không tải được danh sách chi nhánh.';
            msg.className = 'ws-flash err';
        });

        function load() {
            api('/api/complaints/mine').then(function (r) {
                if (r.status !== 200) {
                    throw new Error((r.data && r.data.message) || 'Không tải được khiếu nại của bạn.');
                }
                if (!r.data || !Array.isArray(r.data.items)) {
                    throw new Error('Dữ liệu khiếu nại không hợp lệ.');
                }
                listContainer.replaceChildren();
                if (r.data.items.length === 0) {
                    listContainer.appendChild(el('p', { class: 'ws-muted' }, ['Bạn chưa gửi khiếu nại nào.']));
                    return;
                }
                r.data.items.forEach(function (it) {
                    listContainer.appendChild(el('article', { class: 'ws-card' }, [
                        el('div', { class: 'ws-card-header' }, [
                            el('strong', {}, [it.subject]),
                            statusBadge(it.status)
                        ]),
                        el('div', { class: 'ws-muted small' }, [
                            'Chi nhánh: ' + (it.branchName || 'Chưa xác định')
                        ]),
                        el('div', { class: 'ws-muted small' }, [
                            categoryTag(it.category),
                            'Gửi lúc: ' + formatDate(it.createdAt)
                        ]),
                        el('p', {}, [it.message]),
                        it.managerReply ? el('div', { class: 'ws-callout' }, [
                            el('strong', {}, ['Phản hồi từ Manager']),
                            el('p', {}, [it.managerReply]),
                            el('small', { class: 'ws-muted' }, [formatDate(it.repliedAt)])
                        ]) : null
                    ]));
                });
            }).catch(function (error) {
                listContainer.replaceChildren(el('p', { class: 'ws-flash err' }, [
                    error.message || 'Không tải được khiếu nại của bạn.'
                ]));
            });
        }

        view.appendChild(el('section', { class: 'ws-card' }, [
            el('h3', {}, ['Tạo khiếu nại mới']),
            el('p', { class: 'ws-muted' }, [
                'Khiếu nại này gửi tới chi nhánh bạn chọn, không phải phần bình luận/đánh giá nội dung phim.'
            ]),
            el('label', { class: 'ws-label' }, ['Rạp/chi nhánh', branchSelect]),
            el('label', { class: 'ws-label' }, ['Loại', categorySelect]),
            el('label', { class: 'ws-label' }, ['Tiêu đề', subjectInput]),
            el('label', { class: 'ws-label' }, ['Nội dung', messageInput]),
            el('label', { class: 'ws-label' }, ['Mã vé liên quan (tuỳ chọn)', ticketInput]),
            el('div', { class: 'ws-actions' }, [submitBtn]),
            msg
        ]));
        view.appendChild(el('h3', {}, ['Khiếu nại của tôi']));
        view.appendChild(listContainer);
        load();
        return view;
    }

    function moduleComplaintsManager() {
        var view = el('div', { class: 'ws-page' }, [
            el('div', { class: 'ws-page-header' }, [
                el('span', { class: 'ws-eyebrow' }, ['PHẢN ÁNH DỊCH VỤ RẠP']),
                el('h2', {}, ['Khiếu nại khách hàng']),
                el('p', { class: 'ws-muted' }, [
                    'Xem và phản hồi các khiếu nại thuộc chi nhánh bạn phụ trách.'
                ])
            ])
        ]);
        var listContainer = el('div', {});

        var filterSelect = el('select', { class: 'ws-input' }, [
            el('option', { value: '' }, ['Tất cả']),
            el('option', { value: 'NEW' }, ['Mới tiếp nhận']),
            el('option', { value: 'IN_PROGRESS' }, ['Đang xử lý']),
            el('option', { value: 'RESOLVED' }, ['Đã giải quyết']),
            el('option', { value: 'REJECTED' }, ['Đã từ chối'])
        ]);
        filterSelect.addEventListener('change', load);

        function load() {
            var status = filterSelect.value;
            var qs = status ? ('?status=' + encodeURIComponent(status)) : '';
            api('/api/complaints' + qs).then(function (r) {
                if (r.status !== 200) {
                    throw new Error((r.data && r.data.message) || 'Không tải được danh sách khiếu nại.');
                }
                listContainer.replaceChildren();
                if (!r.data || !Array.isArray(r.data.items) || r.data.items.length === 0) {
                    listContainer.appendChild(el('p', { class: 'ws-muted' }, ['Không có khiếu nại.']));
                    return;
                }
                r.data.items.forEach(function (it) {
                    var replyInput = el('textarea', { class: 'ws-textarea', rows: '2', placeholder: 'Phản hồi...', maxlength: '1000' });
                    var replyBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Gửi phản hồi']);
                    var inProgressBtn = el('button', { class: 'ws-btn ghost', type: 'button' }, ['Đang xử lý']);
                    var resolveBtn = el('button', { class: 'ws-btn success', type: 'button' }, ['Đã giải quyết']);
                    var rejectBtn = el('button', { class: 'ws-btn danger', type: 'button' }, ['Từ chối']);
                    var flash = el('div', { class: 'ws-flash', hidden: true });

                    function action(path, body) {
                        api(path, { method: 'POST', body: body || {} }).then(function (res) {
                            if (res.status !== 200) {
                                throw new Error((res.data && res.data.message) || 'Không thể cập nhật khiếu nại.');
                            }
                            flash.hidden = false;
                            flash.textContent = 'Đã cập nhật khiếu nại.';
                            flash.className = 'ws-flash ok';
                            load();
                        }).catch(function (error) {
                            flash.hidden = false;
                            flash.textContent = error.message || 'Không thể cập nhật khiếu nại.';
                            flash.className = 'ws-flash err';
                        });
                    }

                    replyBtn.addEventListener('click', function () {
                        var r = replyInput.value.trim();
                        if (!r) { flash.hidden = false; flash.textContent = 'Nhập phản hồi'; flash.className = 'ws-flash err'; return; }
                        action('/api/complaints/' + it.id + '/reply', { reply: r });
                    });
                    inProgressBtn.addEventListener('click', function () { action('/api/complaints/' + it.id + '/status', { status: 'IN_PROGRESS' }); });
                    resolveBtn.addEventListener('click', function () { action('/api/complaints/' + it.id + '/status', { status: 'RESOLVED' }); });
                    rejectBtn.addEventListener('click', function () { action('/api/complaints/' + it.id + '/status', { status: 'REJECTED' }); });

                    listContainer.appendChild(el('article', { class: 'ws-card' }, [
                        el('div', { class: 'ws-card-header' }, [
                            el('strong', {}, [it.subject]),
                            statusBadge(it.status)
                        ]),
                        el('div', { class: 'ws-muted small' }, [
                            'Từ: ' + it.userFullName + ' (' + it.userEmail + ') · ',
                            'Chi nhánh: ' + (it.branchName || 'Chưa xác định') + ' · ',
                            categoryTag(it.category),
                            formatDate(it.createdAt)
                        ]),
                        el('p', {}, [it.message]),
                        it.managerReply ? el('div', { class: 'ws-callout' }, [
                            el('strong', {}, ['Phản hồi hiện tại']),
                            el('p', {}, [it.managerReply])
                        ]) : null,
                        el('div', { class: 'ws-form-row' }, [
                            el('label', {}, ['Phản hồi cho khách hàng', replyInput])
                        ]),
                        el('div', { class: 'ws-actions' }, [replyBtn, inProgressBtn, resolveBtn, rejectBtn]),
                        flash
                    ]));
                });
            }).catch(function (error) {
                listContainer.replaceChildren(el('div', { class: 'ws-flash err' }, [
                    error.message || 'Không tải được danh sách khiếu nại.'
                ]));
            });
        }

        view.appendChild(el('div', { class: 'ws-row' }, [
            el('label', { class: 'ws-label' }, ['Trạng thái', filterSelect])
        ]));
        view.appendChild(listContainer);
        load();
        return view;
    }

    // ======================== MOVIE REVIEWS (customer + public detail) ========================
    function moduleReviewsForMovie(movieId, container) {
        if (!movieId) {
            container.appendChild(el('p', { class: 'ws-muted' }, ['Thiếu movieId.']));
            return;
        }
        var role = document.querySelector('meta[name="user-role"]')?.content || '';
        var userIdMeta = document.querySelector('meta[name="user-id"]');
        var currentUserId = userIdMeta ? parseInt(userIdMeta.content, 10) : null;
        var canReview = role === 'CUSTOMER' && !!currentUserId;

        var list = el('div', { class: 'ws-list' });
        var formMsg = null;

        function setFormMessage(text, type) {
            if (!formMsg) return;
            formMsg.hidden = false;
            formMsg.textContent = text;
            formMsg.className = 'ws-flash ' + (type || 'info');
        }

        // Customer form: gửi/cập nhật review của riêng họ.
        var formCard = null;
        if (canReview) {
            var ratingInput = starPicker('rating', 0);
            var commentInput = el('textarea', {
                class: 'ws-textarea ws-review-textarea', rows: '3',
                placeholder: 'Chia sẻ cảm nhận của bạn về phim (tuỳ chọn, tối đa 1000 ký tự)',
                maxlength: '1000'
            });
            var submitBtn = el('button', { class: 'ws-btn primary ws-review-submit', type: 'button' }, ['Gửi đánh giá']);
            var msg = el('div', { class: 'ws-flash', hidden: true });
            formMsg = msg;
            // Banner riêng khi user bấm nút "Sửa" inline trên review item.
            var editBanner = el('div', { class: 'ws-callout ws-callout-info', hidden: true });

            // Submit dùng chung cho cả "Gửi mới" và "Cập nhật".
            submitBtn.addEventListener('click', function () {
                var rating = ratingInput.getValue();
                if (rating < 1 || rating > 5) {
                    setFormMessage('Vui lòng chọn số sao từ 1 đến 5', 'err');
                    return;
                }
                api('/api/reviews', {
                    method: 'POST',
                    body: { movieId: movieId, rating: rating, comment: commentInput.value.trim() }
                }).then(function (res) {
                    if (res.status === 200) {
                        setFormMessage('Đã lưu đánh giá ' + rating + ' sao.', 'ok');
                        // Clear input sau khi gửi thành công để tránh user
                        // gửi nhầm lại. reload() sẽ pre-fill lại nếu là
                        // update review (review của chính user tồn tại).
                        ratingInput.setValue(0);
                        commentInput.value = '';
                        submitBtn.textContent = 'Gửi đánh giá';
                        editBanner.hidden = true;
                        try { formCard.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
                        catch (e) { /* ignore */ }
                        reload();
                    } else {
                        setFormMessage((res.data && res.data.message) || 'Lỗi khi lưu đánh giá.', 'err');
                    }
                });
            });

            // Đếm ký tự còn lại để user biết khi nào sắp tới giới hạn.
            var counter = el('span', { class: 'ws-char-counter' }, ['0/1000']);
            commentInput.addEventListener('input', function () {
                counter.textContent = commentInput.value.length + '/1000';
            });

            formCard = el('section', { class: 'ws-card ws-review-form' }, [
                el('h3', { class: 'ws-review-form-title' }, ['Đánh giá của bạn']),
                editBanner,
                el('div', { class: 'ws-form-row' }, [
                    el('label', { class: 'ws-form-label' }, ['Số sao', ratingInput])
                ]),
                el('div', { class: 'ws-form-row' }, [
                    el('label', { class: 'ws-form-label' }, [
                        'Nhận xét',
                        el('span', { class: 'ws-form-label-hint' }, [' (tuỳ chọn)'])
                    ]),
                    commentInput,
                    el('div', { class: 'ws-textarea-foot' }, [counter])
                ]),
                el('div', { class: 'ws-actions ws-review-actions-row' }, [submitBtn]),
                msg
            ]);
        }

        function reload() {
            api('/api/reviews?movieId=' + encodeURIComponent(movieId)).then(function (r) {
                list.replaceChildren();
                if (r.status !== 200 || !r.data) {
                    list.appendChild(el('p', { class: 'ws-muted' }, ['Không tải được review.']));
                    return;
                }
                var avg = r.data.average || 0;
                var total = r.data.total || 0;
                var headerSummary = el('div', { class: 'ws-summary' }, [
                    el('span', { class: 'ws-stars' }, [starString(avg)]),
                    el('strong', {}, [(avg).toFixed(1) + '/5']),
                    el('span', { class: 'ws-muted' }, [' · ' + total + ' đánh giá'])
                ]);
                // Replace existing summary if any
                container.querySelectorAll('.ws-summary').forEach(function (n) { n.remove(); });
                container.insertBefore(headerSummary, list);

                if (!r.data.items || r.data.items.length === 0) {
                    list.appendChild(el('p', { class: 'ws-muted' }, ['Chưa có đánh giá nào. Hãy là người đầu tiên!']));
                } else {
                    var myExisting = null;
                    r.data.items.forEach(function (it) {
                        var isMine = currentUserId && String(it.userId) === String(currentUserId);
                        if (isMine) myExisting = it;

                        // Actions cho review của chính user (Sửa / Xoá inline).
                        var actions = [];
                        if (isMine) {
                            var editBtn = el('button', {
                                type: 'button',
                                class: 'ws-btn ghost xs',
                                'aria-label': 'Sửa đánh giá'
                            }, ['✏️ Sửa']);
                            editBtn.addEventListener('click', function () { startEdit(it); });
                            actions.push(editBtn);

                            var deleteBtn = el('button', {
                                type: 'button',
                                class: 'ws-btn ghost xs danger',
                                'aria-label': 'Xoá đánh giá'
                            }, ['🗑 Xoá']);
                            deleteBtn.addEventListener('click', function () {
                                deleteBtn.disabled = true;
                                deleteBtn.textContent = 'Đang xoá…';
                                api('/api/reviews/' + it.id, { method: 'DELETE' }).then(function (del) {
                                    var deleted = del.status === 200;
                                    var errorMessage = del.data && del.data.message;
                                    setFormMessage(deleted
                                        ? 'Đã xoá đánh giá của bạn.'
                                        : (errorMessage || ('Không thể xoá đánh giá (mã lỗi ' + del.status + ').')),
                                        deleted ? 'ok' : 'err'
                                    );
                                    if (deleted) {
                                        ratingInput.setValue(0);
                                        commentInput.value = '';
                                        submitBtn.textContent = 'Gửi đánh giá';
                                        editBanner.hidden = true;
                                        reload();
                                    } else {
                                        deleteBtn.disabled = false;
                                        deleteBtn.textContent = '🗑 Xoá';
                                    }
                                }).catch(function (err) {
                                    setFormMessage(err.message || 'Lỗi khi xoá đánh giá.', 'err');
                                    deleteBtn.disabled = false;
                                    deleteBtn.textContent = '🗑 Xoá';
                                });
                            });
                            actions.push(deleteBtn);
                        }

                        list.appendChild(el('article', { class: 'ws-card small ws-review-item' + (isMine ? ' is-mine' : '') }, [
                            el('div', { class: 'ws-card-header' }, [
                                el('strong', {}, [it.userFullName + (isMine ? ' (Bạn)' : '')]),
                                el('span', { class: 'ws-star-display' }, [starString(it.rating)])
                            ]),
                            it.comment ? el('p', { class: 'ws-review-comment' }, [it.comment]) : null,
                            el('div', { class: 'ws-review-meta' }, [
                                el('small', { class: 'ws-muted' }, [formatDate(it.createdAt)]),
                                actions.length ? el('div', { class: 'ws-review-actions' }, actions) : null
                            ])
                        ]));
                    });
                    // Pre-fill form nếu user đã review
                    if (canReview && formCard && myExisting) {
                        ratingInput.setValue(myExisting.rating);
                        commentInput.value = myExisting.comment || '';
                        submitBtn.textContent = 'Cập nhật đánh giá';
                    }
                }
            });
        }

        // Đưa form về chế độ "Sửa" và pre-fill từ review hiện có.
        function startEdit(it) {
            ratingInput.setValue(it.rating);
            commentInput.value = it.comment || '';
            submitBtn.textContent = 'Lưu thay đổi';
            // Hiện banner trên form cho user biết đang sửa review của họ.
            editBanner.textContent = 'Bạn đang chỉnh sửa đánh giá của bạn. Bấm "Lưu thay đổi" để cập nhật.';
            editBanner.hidden = false;
            try {
                formCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
                commentInput.focus({ preventScroll: true });
            } catch (e) { /* ignore */ }
        }

        if (formCard) container.appendChild(formCard);
        container.appendChild(list);
        reload();
    }

    // ======================== CUSTOMER POINTS (Staff adjust) ========================
    function moduleCustomerPoints() {
        var view = el('div', { class: 'ws-page' }, [
            el('div', { class: 'ws-page-header' }, [
                el('span', { class: 'ws-eyebrow' }, ['ĐIỂM THƯỞNG']),
                el('h2', {}, ['Điều chỉnh điểm khách hàng'])
            ]),
            el('p', { class: 'ws-muted' }, ['Tra cứu điểm hiện tại của khách hàng theo email, sau đó cộng/trừ điểm kèm lý do (tối thiểu 5 ký tự). Mọi thao tác được ghi vào lịch sử.'])
        ]);

        var emailInput = el('input', { type: 'email', class: 'ws-input', placeholder: 'email@khachhang.com' });
        var lookupBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Tra cứu']);
        var flash = el('div', { class: 'ws-flash', hidden: true });
        var lookupResult = el('div', {});
        var adjustCard = el('div', { hidden: true });

        lookupBtn.addEventListener('click', function () {
            var email = emailInput.value.trim();
            if (!email) { showFlash(flash, 'err', 'Nhập email khách hàng'); return; }
            lookupBtn.disabled = true;
            lookupBtn.textContent = 'Đang tra…';
            api('/loyalty/customer-points?email=' + encodeURIComponent(email))
                .then(function (r) {
                    lookupBtn.disabled = false; lookupBtn.textContent = 'Tra cứu';
                    if (r.status !== 200 || !r.data) {
                        var msg = (r.data && r.data.message) ? r.data.message : 'Không tìm thấy khách hàng';
                        showFlash(flash, 'err', msg);
                        lookupResult.replaceChildren();
                        adjustCard.hidden = true;
                        return;
                    }
                    showFlash(flash, 'ok', 'Đã tra cứu');
                    renderCustomerCard(lookupResult, r.data);
                    renderAdjustForm(adjustCard, r.data, function () {
                        // Sau khi điều chỉnh thành công → cập nhật lại điểm hiện tại
                        api('/loyalty/customer-points?email=' + encodeURIComponent(email)).then(function (r2) {
                            if (r2.status === 200 && r2.data) {
                                renderCustomerCard(lookupResult, r2.data);
                                renderAdjustForm(adjustCard, r2.data, arguments.callee);
                            }
                        });
                    });
                    adjustCard.hidden = false;
                });
        });

        emailInput.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); lookupBtn.click(); }
        });

        var searchCard = el('section', { class: 'ws-card' }, [
            el('h3', {}, ['Tra cứu khách hàng']),
            el('label', { class: 'ws-label' }, ['Email khách hàng', emailInput]),
            el('div', { class: 'ws-actions' }, [lookupBtn]),
            flash
        ]);

        view.appendChild(searchCard);
        view.appendChild(lookupResult);
        view.appendChild(adjustCard);
        return view;
    }

    function renderCustomerCard(host, customer) {
        host.replaceChildren(el('section', { class: 'ws-card' }, [
            el('h3', {}, ['Thông tin điểm']),
            el('div', { class: 'ws-stat-row' }, [
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Khách hàng']),
                    el('div', { class: 'ws-stat-value' }, [customer.fullName || '—'])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Email']),
                    el('div', { class: 'ws-stat-value small' }, [customer.email || '—'])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Hạng']),
                    el('div', { class: 'ws-stat-value' }, [customer.tier || '—'])
                ]),
                el('div', { class: 'ws-stat primary' }, [
                    el('div', { class: 'ws-stat-label' }, ['Điểm hiện tại']),
                    el('div', { class: 'ws-stat-value big' }, [String(customer.points ?? 0)])
                ])
            ])
        ]));
    }

    function renderAdjustForm(host, customer, onSuccess) {
        var deltaInput = el('input', { type: 'number', class: 'ws-input', placeholder: 'VD: +50 hoặc -20', step: '1' });
        var reasonInput = el('textarea', { class: 'ws-textarea', rows: '2', placeholder: 'Lý do điều chỉnh (tối thiểu 5 ký tự)', maxlength: '500' });
        var submitBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Xác nhận điều chỉnh']);
        var flash = el('div', { class: 'ws-flash', hidden: true });

        submitBtn.addEventListener('click', function () {
            var delta = parseInt((deltaInput.value || '').trim(), 10);
            var reason = (reasonInput.value || '').trim();
            if (isNaN(delta) || delta === 0) { showFlash(flash, 'err', 'Nhập số điểm (+/-)'); return; }
            if (reason.length < 5) { showFlash(flash, 'err', 'Lý do tối thiểu 5 ký tự'); return; }
            submitBtn.disabled = true;
            api('/loyalty/adjust', {
                method: 'POST',
                body: { customerId: customer.id, delta: delta, reason: reason }
            }).then(function (r) {
                submitBtn.disabled = false;
                if (r.status !== 200 || !r.data) {
                    var msg = (r.data && r.data.message) ? r.data.message : 'Điều chỉnh thất bại';
                    showFlash(flash, 'err', msg);
                    return;
                }
                var newBalance = r.data.newBalance != null ? r.data.newBalance : '?';
                showFlash(flash, 'ok', 'Đã điều chỉnh. Số dư mới: ' + newBalance + ' điểm.');
                deltaInput.value = '';
                reasonInput.value = '';
                if (typeof onSuccess === 'function') onSuccess();
            });
        });

        host.replaceChildren(el('section', { class: 'ws-card' }, [
            el('h3', {}, ['Điều chỉnh điểm']),
            el('label', { class: 'ws-label' }, ['Điểm thay đổi (+/-)', deltaInput]),
            el('label', { class: 'ws-label' }, ['Lý do', reasonInput]),
            el('div', { class: 'ws-actions' }, [submitBtn]),
            flash
        ]));
    }

    function showFlash(host, type, message) {
        host.hidden = false;
        host.textContent = message;
        host.className = 'ws-flash ' + type;
    }

    // ======================== TICKETS (Staff/Manager) ========================
    function moduleTicketStaff() {
        var view = el('div', { class: 'ws-page' }, [
            el('div', { class: 'ws-page-header' }, [
                el('span', { class: 'ws-eyebrow' }, ['VÉ']),
                el('h2', {}, ['Quản lý vé'])
            ]),
            el('p', { class: 'ws-muted' }, ['Tra cứu nhanh theo mã vé hoặc xem danh sách vé đã bán trong chi nhánh.'])
        ]);

        // ----- Form tra cứu nhanh -----
        var codeInput = el('input', { type: 'text', class: 'ws-input', placeholder: 'Nhập mã vé (VD: TCK-...)', 'aria-label': 'Mã vé' });
        var lookupBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Tra cứu']);
        var lookupFlash = el('div', { class: 'ws-flash', hidden: true });
        var lookupResult = el('div', {});

        lookupBtn.addEventListener('click', function () {
            var code = codeInput.value.trim();
            if (!code) { showFlash(lookupFlash, 'err', 'Nhập mã vé'); return; }
            lookupBtn.disabled = true; lookupBtn.textContent = 'Đang tra…';
            api('/booking/lookup?code=' + encodeURIComponent(code)).then(function (r) {
                lookupBtn.disabled = false; lookupBtn.textContent = 'Tra cứu';
                if (r.status !== 200 || !r.data) {
                    var msg = (r.data && r.data.message) ? r.data.message : 'Không tìm thấy vé';
                    showFlash(lookupFlash, 'err', msg);
                    lookupResult.replaceChildren();
                    return;
                }
                showFlash(lookupFlash, 'ok', 'Đã tra cứu vé ' + code);
                renderTicketDetail(lookupResult, r.data);
            });
        });

        codeInput.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); lookupBtn.click(); }
        });

        // ----- Filter danh sách vé -----
        var branchFilter = el('select', { class: 'ws-input' });
        var reloadListBtn = el('button', { class: 'ws-btn ghost', type: 'button' }, ['Tải lại']);
        var listContainer = el('div', { class: 'ws-table-wrap' });
        var listFlash = el('div', { class: 'ws-flash', hidden: true });

        // Staff/Manager đã bị giới hạn ở backend theo branchId — filter chỉ để thuận tiện nếu admin
        function loadList() {
            listContainer.replaceChildren(el('p', { class: 'ws-muted' }, ['Đang tải…']));
            api('/booking/list?limit=100').then(function (r) {
                listContainer.replaceChildren();
                if (r.status !== 200 || !r.data || !r.data.items) {
                    showFlash(listFlash, 'err', (r.data && r.data.message) || 'Không tải được danh sách vé');
                    return;
                }
                var items = r.data.items;
                if (items.length === 0) {
                    listContainer.appendChild(el('p', { class: 'ws-muted' }, ['Chưa có vé nào trong phạm vi chi nhánh.']));
                    return;
                }
                var table = el('table', { class: 'ws-table' }, [
                    el('thead', {}, [el('tr', {}, [
                        el('th', {}, ['Mã vé']),
                        el('th', {}, ['Khách hàng']),
                        el('th', {}, ['Phim']),
                        el('th', {}, ['Suất chiếu']),
                        el('th', {}, ['Số tiền']),
                        el('th', {}, ['Trạng thái']),
                        el('th', {}, [''])
                    ])])
                ]);
                var tbody = el('tbody', {});
                items.forEach(function (it) {
                    var row = el('tr', {}, [
                        el('td', {}, [el('code', {}, [it.ticketCode || '—'])]),
                        el('td', {}, [it.customerName || '—']),
                        el('td', {}, [it.movieTitle || '—']),
                        el('td', {}, [formatDate(it.showtimeStart)]),
                        el('td', {}, [formatMoney(it.totalAmount)]),
                        el('td', {}, [ticketStatusBadge(it.status)])
                    ]);
                    var detailBtn = el('button', { class: 'ws-btn ghost small', type: 'button' }, ['Chi tiết']);
                    detailBtn.addEventListener('click', function () {
                        codeInput.value = it.ticketCode || '';
                        lookupBtn.click();
                        codeInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    });
                    row.appendChild(el('td', {}, [detailBtn]));
                    tbody.appendChild(row);
                });
                table.appendChild(tbody);
                listContainer.appendChild(table);
            });
        }

        reloadListBtn.addEventListener('click', loadList);

        var lookupCard = el('section', { class: 'ws-card' }, [
            el('h3', {}, ['Tra cứu theo mã vé']),
            el('label', { class: 'ws-label' }, ['Mã vé', el('div', { class: 'ws-row' }, [codeInput, lookupBtn])]),
            lookupFlash,
            lookupResult
        ]);

        var listCard = el('section', { class: 'ws-card' }, [
            el('div', { class: 'ws-card-header' }, [
                el('h3', {}, ['Danh sách vé đã bán']),
                reloadListBtn
            ]),
            listFlash,
            listContainer
        ]);

        view.appendChild(lookupCard);
        view.appendChild(listCard);
        loadList();
        return view;
    }

    function renderTicketDetail(host, t) {
        host.replaceChildren(el('section', { class: 'ws-card' }, [
            el('h3', {}, ['Chi tiết vé ' + (t.ticketCode || ('#' + t.id))]),
            el('div', { class: 'ws-stat-row' }, [
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Khách hàng']),
                    el('div', { class: 'ws-stat-value' }, [t.customerName || 'Khách lẻ'])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Email']),
                    el('div', { class: 'ws-stat-value small' }, [t.customerEmail || '—'])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Phim']),
                    el('div', { class: 'ws-stat-value' }, [t.movieTitle || '—'])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Phòng chiếu']),
                    el('div', { class: 'ws-stat-value' }, [t.screenName || '—'])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Suất chiếu']),
                    el('div', { class: 'ws-stat-value' }, [formatDate(t.showtimeStart)])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Chi nhánh']),
                    el('div', { class: 'ws-stat-value' }, [t.branchName || '—'])
                ]),
                el('div', { class: 'ws-stat primary' }, [
                    el('div', { class: 'ws-stat-label' }, ['Số tiền']),
                    el('div', { class: 'ws-stat-value' }, [formatMoney(t.totalAmount)])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Trạng thái']),
                    el('div', { class: 'ws-stat-value' }, [ticketStatusBadge(t.status)])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Điểm tích luỹ']),
                    el('div', { class: 'ws-stat-value' }, [t.pointsEarned != null ? String(t.pointsEarned) : '—'])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Tạo lúc']),
                    el('div', { class: 'ws-stat-value small' }, [formatDate(t.createdAt)])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Xác nhận lúc']),
                    el('div', { class: 'ws-stat-value small' }, [formatDate(t.confirmedAt)])
                ]),
                el('div', { class: 'ws-stat' }, [
                    el('div', { class: 'ws-stat-label' }, ['Sử dụng lúc']),
                    el('div', { class: 'ws-stat-value small' }, [formatDate(t.usedAt)])
                ])
            ])
        ]));
    }

    function ticketStatusBadge(status) {
        var cls = 'ws-badge ';
        var label = status || '?';
        switch (status) {
            case 'CONFIRMED': cls += 'success'; return el('span', { class: cls }, ['Đã xác nhận']);
            case 'USED':      cls += 'info';    return el('span', { class: cls }, ['Đã sử dụng']);
            case 'CANCELLED': cls += 'danger';  return el('span', { class: cls }, ['Đã huỷ']);
            case 'PENDING':   cls += 'warning'; return el('span', { class: cls }, ['Chờ']);
            case 'HELD':      cls += 'warning'; return el('span', { class: cls }, ['Đang giữ']);
            default: return el('span', { class: cls }, [label]);
        }
    }

    function formatMoney(v) {
        if (v == null) return '—';
        var n = Number(v);
        if (isNaN(n)) return '—';
        return new Intl.NumberFormat('vi-VN').format(n) + ' ₫';
    }

    // Public registration
    window.CinemaHubPages = window.CinemaHubPages || {};
    window.CinemaHubPages['support'] = moduleSupport;
    window.CinemaHubPages['support-inbox'] = moduleSupportInbox;
    window.CinemaHubPages['complaints'] = moduleComplaints;
    window.CinemaHubPages['customer-points'] = moduleCustomerPoints;
    // Expose moduleTicketStaff globally so console.js can dispatch for module=ticket.
    window.moduleTicketStaff = moduleTicketStaff;
    window.CinemaHubPages['reviews'] = function (ctx) {
        var params = new URLSearchParams(window.location.search);
        var movieId = params.get('movieId');
        var view = el('div', { class: 'ws-page' }, [
            el('div', { class: 'ws-page-header' }, [
                el('span', { class: 'ws-eyebrow' }, ['ĐÁNH GIÁ PHIM']),
                el('h2', {}, ['Đánh giá & nhận xét'])
            ])
        ]);
        if (movieId) {
            moduleReviewsForMovie(movieId, view);
            return view;
        }

        var movieSelect = el('select', { class: 'ws-input', 'aria-label': 'Chọn phim để đánh giá' }, [
            el('option', { value: '' }, ['— Chọn phim —'])
        ]);
        var selectedReviews = el('div', {});
        var selectorCard = el('section', { class: 'ws-card' }, [
            el('label', { class: 'ws-label' }, ['Chọn phim đã xem', movieSelect])
        ]);
        view.appendChild(selectorCard);
        view.appendChild(selectedReviews);

        function renderSelectedMovie() {
            var selectedId = movieSelect.value;
            params.set('module', 'reviews');
            if (selectedId) params.set('movieId', selectedId);
            else params.delete('movieId');
            history.replaceState({}, '', window.location.pathname + '?' + params.toString());
            selectedReviews.replaceChildren();
            if (selectedId) moduleReviewsForMovie(selectedId, selectedReviews);
        }
        movieSelect.addEventListener('change', renderSelectedMovie);

        api('/movie').then(function (result) {
            if (result.status !== 200 || !Array.isArray(result.data)) {
                throw new Error((result.data && result.data.message) || 'Không tải được danh sách phim.');
            }
            result.data.forEach(function (movie) {
                movieSelect.appendChild(el('option', { value: String(movie.id) }, [movie.title || ('Phim #' + movie.id)]));
            });
            if (params.get('movieId')) {
                movieSelect.value = params.get('movieId');
                if (movieSelect.value) renderSelectedMovie();
            }
        }).catch(function (error) {
            selectorCard.appendChild(el('p', { class: 'ws-flash err' }, [error.message || 'Không tải được danh sách phim.']));
        });
        return view;
    };
    // Public API cho movie-detail page nhúng.
    window.CinemaHubReviews = { renderForMovie: moduleReviewsForMovie };
})();
