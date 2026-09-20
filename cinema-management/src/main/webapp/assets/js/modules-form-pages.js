/**
 * modules-form-pages.js — Đăng ký form-pages cho tất cả modules (Promise-chain style).
 *
 * Mỗi module khai báo:
 *   - list: render list page với search/filter/sort/pagination
 *   - create: render form page create
 *   - edit: render form page edit (load data trước)
 *
 * Đăng ký qua window.CinemaHubRouter.register(module, { list, create, edit }).
 *
 * Pattern: hub.api() trả Promise → dùng .then()/.catch() chain.
 * KHÔNG dùng async/await (giữ code style đồng nhất với Java Servlet phía server).
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    var router = window.CinemaHubRouter;
    var list = window.CinemaHubList;
    var form = window.CinemaHubForm;
    if (!hub || !router || !list || !form) return;

    var ctx = hub.ctx || '';
    var STATUS_MAP = {
        'PAID': 'green', 'CONFIRMED': 'green', 'USED': 'purple', 'SELLING': 'blue',
        'OPEN': 'blue', 'SOLD_OUT': 'red', 'ALMOST_FULL': 'orange', 'PENDING': 'orange',
        'CANCELLED': 'red', 'ENDED': 'gray', 'INACTIVE': 'red', 'ACTIVE': 'green',
        'EXPIRED': 'gray', 'HOLD': 'orange', 'FAILED': 'red',
        'PENDING_PAYMENT': 'orange', 'FULFILLED': 'purple', 'READY_FOR_PICKUP': 'green',
        'EXPIRED_NO_SHOW': 'gray', 'LOCKED': 'red', 'REJECTED': 'red', 'APPROVED': 'green',
        'CLOSED': 'gray', 'DRAFT': 'gray', 'PUBLISHED': 'green', 'ARCHIVED': 'gray'
    };

    function statusBadge(label) {
        var tone = STATUS_MAP[label] || 'gray';
        var wrap = document.createElement('span');
        wrap.className = 'ws-badge ws-status-pill ws-badge-' + tone;
        wrap.textContent = label || '';
        return wrap;
    }

    function confirmAction(message, onYes) {
        if (window.confirm(message)) onYes();
    }

    function notifyError(err) {
        hub.notify(err.message || 'Đã xảy ra lỗi.', 'err');
    }

    function dateValue(value) {
        if (!value) return '';
        var text = String(value);
        return text.length >= 10 ? text.slice(0, 10) : text;
    }

    // ================================================================
    //  USERS  (Admin only)
    // ================================================================
    router.register('users', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta !== 'ADMIN') {
                var viewRoot = document.getElementById('viewRoot');
                viewRoot.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không có quyền truy cập']),
                    hub.el('p', {}, ['Chỉ Admin mới được quản lý người dùng.'])
                ]));
                return;
            }
            list.render({
                title: 'Người dùng',
                subtitle: 'Quản lý tài khoản nhân viên và khách hàng CinemaHub.',
                addUrl: ctx + '/console?module=users&action=create',
                addLabel: 'Tạo người dùng',
                searchPlaceholder: 'Tìm theo email, số điện thoại, họ tên…',
                pageSize: 20,
                filters: [{
                    name: 'role', label: 'Vai trò',
                    options: [
                        { value: 'ADMIN', label: 'Admin' },
                        { value: 'BRANCH_MANAGER', label: 'Quản lý chi nhánh' },
                        { value: 'BRANCH_STAFF', label: 'Nhân viên chi nhánh' },
                        { value: 'CUSTOMER', label: 'Khách hàng' }
                    ]
                }],
                fetcher: function (qs) {
                    return hub.api('/api/users?' + qs.toString());
                },
                columns: [
                    { label: 'ID', key: 'id' },
                    { label: 'Email', key: 'email' },
                    { label: 'Số điện thoại', key: 'phone' },
                    { label: 'Họ tên', key: 'fullName' },
                    { label: 'Vai trò', render: function (r) {
                        var map = {
                            'ADMIN': 'Admin',
                            'BRANCH_MANAGER': 'Quản lý chi nhánh',
                            'BRANCH_STAFF': 'Nhân viên',
                            'CUSTOMER': 'Khách hàng'
                        };
                        var span = document.createElement('span');
                        span.textContent = map[r.role] || r.role || '—';
                        return span;
                    }},
                    { label: 'Trạng thái', render: function (r) { return statusBadge(r.status); } },
                    { label: 'Ngày tạo', render: function (r) { return r.createdAt ? hub.fmtDateTime(r.createdAt) : '—'; } }
                ],
                actions: function (row) {
                    var arr = [
                        { label: 'Sửa', class: 'secondary', href: ctx + '/console?module=users&action=edit&id=' + row.id }
                    ];
                    if (row.status === 'ACTIVE') {
                        arr.push({ label: 'Khóa', class: 'danger', onClick: function () {
                            confirmAction('Khóa tài khoản ' + row.email + '?', function () {
                                hub.api('/api/users/' + row.id + '/status', { method: 'POST', body: { status: 'LOCKED' } })
                                    .then(function () {
                                        hub.notify('Đã khóa tài khoản.', 'ok');
                                        router.go('users');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    } else {
                        arr.push({ label: 'Mở khóa', class: 'secondary', onClick: function () {
                            confirmAction('Mở khóa tài khoản ' + row.email + '?', function () {
                                hub.api('/api/users/' + row.id + '/status', { method: 'POST', body: { status: 'ACTIVE' } })
                                    .then(function () {
                                        hub.notify('Đã mở khóa.', 'ok');
                                        router.go('users');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        create: function () {
            form.renderFormPage({
                breadcrumb: ['Quản lý', { label: 'Người dùng', href: ctx + '/console?module=users' }, 'Tạo mới'],
                title: 'Tạo người dùng mới',
                subtitle: 'Tạo tài khoản nhân viên hoặc khách hàng cho hệ thống CinemaHub.',
                submitLabel: 'Tạo người dùng',
                sections: [{
                    title: 'Thông tin cơ bản',
                    description: 'Các trường có dấu * là bắt buộc. Hệ thống sẽ tự sinh mật khẩu ngẫu nhiên và gửi thông tin đăng nhập qua email cho user.',
                    fields: [
                        { name: 'fullName', label: 'Họ và tên', required: true, placeholder: 'Nguyễn Văn A' },
                        { name: 'email', label: 'Email', type: 'email', required: true, placeholder: 'user@example.com', autocomplete: 'email' },
                        { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, pattern: '0[0-9]{9}', patternMessage: 'Số điện thoại phải bắt đầu bằng 0 và có 10 chữ số', placeholder: '0912345678', autocomplete: 'tel' },
                        { name: 'role', label: 'Vai trò', type: 'select', required: true, options: [
                            { value: 'CUSTOMER', label: 'Khách hàng' },
                            { value: 'BRANCH_STAFF', label: 'Nhân viên chi nhánh' },
                            { value: 'BRANCH_MANAGER', label: 'Quản lý chi nhánh' },
                            { value: 'ADMIN', label: 'Admin' }
                        ]}
                    ]
                }],
                onSubmit: function (data) {
                    hub.api('/api/users', { method: 'POST', body: data })
                        .then(function (resp) {
                            if (resp && resp.emailSent) {
                                hub.notify('Đã tạo user và gửi email chứa mật khẩu tạm thời.', 'ok');
                            } else if (resp && resp.generatedPassword) {
                                hub.notify('Đã tạo user. Email gửi thất bại — mật khẩu tạm: ' + resp.generatedPassword, 'warn');
                            } else {
                                hub.notify('Đã tạo user.', 'ok');
                            }
                            router.go('users');
                        })
                        .catch(notifyError);
                },
                onCancel: function () { router.go('users'); }
            });
        },
        edit: function (id) {
            hub.api('/api/users/' + id)
                .then(function (user) {
                    form.renderFormPage({
                        breadcrumb: ['Quản lý', { label: 'Người dùng', href: ctx + '/console?module=users' }, 'Chỉnh sửa'],
                        title: 'Chỉnh sửa người dùng',
                        subtitle: (user.fullName || user.email || '') + ' · #' + user.id,
                        submitLabel: 'Lưu thay đổi',
                        sections: [{
                            title: 'Thông tin cơ bản',
                            fields: [
                                { name: 'fullName', label: 'Họ và tên', required: true, value: user.fullName },
                                { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, value: user.phone, pattern: '0[0-9]{9}' },
                                { name: 'status', label: 'Trạng thái', type: 'select', required: true, value: user.status, options: [
                                    { value: 'ACTIVE', label: 'Hoạt động' },
                                    { value: 'LOCKED', label: 'Bị khóa' },
                                    { value: 'INACTIVE', label: 'Ngưng' }
                                ]}
                            ]
                        }],
                        onSubmit: function (data) {
                            hub.api('/api/users/' + id, { method: 'PUT', body: data })
                                .then(function () { router.go('users'); })
                                .catch(notifyError);
                        },
                        onCancel: function () { router.go('users'); }
                    });
                })
                .catch(notifyError);
        }
    });

    // ================================================================
    //  BRANCH (Admin only)
    // ================================================================
    router.register('branch', {
        list: function () {
            list.render({
                title: 'Chi nhánh',
                subtitle: 'Danh sách chi nhánh trong hệ thống CinemaHub.',
                addUrl: ctx + '/console?module=branch&action=create',
                addLabel: 'Tạo chi nhánh',
                searchPlaceholder: 'Tìm theo tên, địa chỉ, SĐT…',
                pageSize: 20,
                filters: [{
                    name: 'status', label: 'Trạng thái',
                    options: [
                        { value: 'ACTIVE', label: 'Hoạt động' },
                        { value: 'INACTIVE', label: 'Ngưng hoạt động' }
                    ]
                }],
                sortable: true,
                sortOptions: [
                    { value: 'name', label: 'Tên A→Z' },
                    { value: 'name:desc', label: 'Tên Z→A' },
                    { value: 'createdAt', label: 'Mới nhất' }
                ],
                fetcher: function (qs) { return hub.api('/branch/search?' + qs.toString()); },
                emptyTitle: 'Chưa có chi nhánh',
                emptyMessage: 'Hãy tạo chi nhánh đầu tiên để bắt đầu vận hành.',
                columns: [
                    { label: 'ID', key: 'id', width: '70px' },
                    { label: 'Tên chi nhánh', key: 'name' },
                    { label: 'Địa chỉ', key: 'address' },
                    { label: 'Số điện thoại', key: 'phone' },
                    { label: 'Trạng thái', render: function (r) { return statusBadge(r.status); } }
                ],
                actions: function (row) {
                    var arr = [{ label: 'Sửa', class: 'secondary', href: ctx + '/console?module=branch&action=edit&id=' + row.id }];
                    if (row.status === 'ACTIVE') {
                        arr.push({ label: 'Ngưng', class: 'danger', onClick: function () {
                            confirmAction('Ngưng hoạt động chi nhánh này?', function () {
                                hub.api('/branch/' + row.id, { method: 'PUT', body: { action: 'deactivate' } })
                                    .then(function () {
                                        hub.notify('Đã ngưng hoạt động chi nhánh.', 'ok');
                                        router.go('branch');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        create: function () {
            form.renderFormPage({
                breadcrumb: ['Quản lý', { label: 'Chi nhánh', href: ctx + '/console?module=branch' }, 'Tạo mới'],
                title: 'Tạo chi nhánh mới',
                subtitle: 'Thêm một địa điểm rạp chiếu phim vào hệ thống.',
                submitLabel: 'Tạo chi nhánh',
                sections: [{
                    title: 'Thông tin chi nhánh',
                    fields: [
                        { name: 'name', label: 'Tên chi nhánh', required: true, placeholder: 'VD: Cinema Hà Đông' },
                        { name: 'address', label: 'Địa chỉ', required: true, placeholder: 'Số nhà, đường, phường, quận, TP' },
                        { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, pattern: '0[0-9]{9}', placeholder: '0243xxxxxx' }
                    ]
                }],
                onSubmit: function (data) {
                    hub.api('/branch', { method: 'POST', body: data })
                        .then(function () { router.go('branch'); })
                        .catch(notifyError);
                },
                onCancel: function () { router.go('branch'); }
            });
        },
        edit: function (id) {
            hub.api('/branch')
                .then(function (list) {
                    var branch = (list || []).find(function (b) { return String(b.id) === String(id); });
                    if (!branch) throw new Error('Không tìm thấy chi nhánh');
                    form.renderFormPage({
                        breadcrumb: ['Quản lý', { label: 'Chi nhánh', href: ctx + '/console?module=branch' }, 'Chỉnh sửa'],
                        title: 'Chỉnh sửa chi nhánh',
                        subtitle: branch.name,
                        submitLabel: 'Lưu thay đổi',
                        sections: [{
                            title: 'Thông tin chi nhánh',
                            fields: [
                                { name: 'name', label: 'Tên chi nhánh', required: true, value: branch.name },
                                { name: 'address', label: 'Địa chỉ', required: true, value: branch.address },
                                { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, value: branch.phone, pattern: '0[0-9]{9}' }
                            ]
                        }],
                        onSubmit: function (data) {
                            hub.api('/branch/' + id, { method: 'PUT', body: data })
                                .then(function () { router.go('branch'); })
                                .catch(notifyError);
                        },
                        onCancel: function () { router.go('branch'); }
                    });
                })
                .catch(notifyError);
        }
    });

    // ================================================================
    //  MOVIE (Admin only)
    // ================================================================
    function renderMovieForm(movie) {
        var isEdit = !!movie;

        // Trạng thái poster: lưu URL hiện tại; nếu upload mới thì thay đổi URL này.
        // Khi không upload ảnh mới, URL cũ được giữ nguyên → không làm mất ảnh cũ.
        var posterState = {
            currentUrl: (movie && movie.posterUrl) || '',
            pendingFile: null
        };

        var sections = [{
            title: 'Thông tin cơ bản',
            description: 'Mô tả và chi tiết phim.',
            fields: [
                { name: 'title', label: 'Tên phim', required: true, value: movie && movie.title, fullWidth: true },
                { name: 'author', label: 'Tác giả / Đạo diễn', required: false,
                  value: movie && movie.author, placeholder: 'VD: Christopher Nolan, Trần Anh Hùng…',
                  hint: 'Thông tin tác giả / đạo diễn (metadata bổ sung).' },
                { name: 'durationMin', label: 'Thời lượng (phút)', type: 'number', min: 1, max: 600, value: movie && movie.durationMin },
                { name: 'genre', label: 'Thể loại', value: movie && movie.genre, placeholder: 'Hành động, Tình cảm, …' },
                { name: 'rating', label: 'Giới hạn tuổi', type: 'select', required: true, value: (movie && movie.rating) || 'P', options: [
                    { value: 'P', label: 'P - Mọi lứa tuổi' },
                    { value: 'C13', label: 'C13 - Trên 13 tuổi' },
                    { value: 'C16', label: 'C16 - Trên 16 tuổi' },
                    { value: 'C18', label: 'C18 - Trên 18 tuổi' }
                ]},
                { name: 'releaseDate', label: 'Ngày phát hành', type: 'date', required: true, value: dateValue(movie && movie.releaseDate) },
                { name: 'endDate', label: 'Ngày kết thúc', type: 'date', required: true, value: dateValue(movie && movie.endDate) },
                { name: 'status', label: 'Trạng thái', type: 'select', required: true, value: (movie && movie.status) || 'DRAFT', options: [
                    { value: 'DRAFT', label: 'Bản nháp' },
                    { value: 'PUBLISHED', label: 'Đang phát hành' },
                    { value: 'ARCHIVED', label: 'Đã lưu trữ' }
                ]},
                { name: 'description', label: 'Mô tả', type: 'textarea', required: false, value: movie && movie.description, rows: 5, fullWidth: true,
                  hint: 'Mô tả ngắn gọn nội dung phim để hiển thị cho khách hàng.' }
            ]
        }];

        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Phim', href: ctx + '/console?module=movie' }, isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa phim' : 'Thêm phim mới',
            subtitle: isEdit ? movie.title : 'Tạo bản ghi phim mới trong hệ thống.',
            submitLabel: isEdit ? 'Lưu thay đổi' : 'Tạo phim',
            sections: sections,
            // renderFooter: chèn khối poster upload + hidden posterUrl sau form (sau sections)
            renderFooter: function (formEl) {
                var wrap = hub.el('section', { class: 'ws-form-section ws-form-poster-section' });
                wrap.appendChild(hub.el('h2', { class: 'ws-form-section-title' }, ['Poster phim']));
                wrap.appendChild(hub.el('p', { class: 'ws-form-section-desc' },
                    ['Chọn ảnh poster để upload qua Cloudinary. Nếu không chọn ảnh mới, hệ thống sẽ giữ nguyên poster hiện tại.']));

                var grid = hub.el('div', { class: 'ws-poster-uploader' });

                // Preview box
                var preview = hub.el('div', { class: 'ws-poster-preview', id: 'moviePosterPreview' });
                function renderPreview() {
                    preview.replaceChildren();
                    if (posterState.currentUrl) {
                        var img = hub.el('img', { src: posterState.currentUrl, alt: 'Poster' });
                        preview.appendChild(img);
                    } else {
                        preview.appendChild(hub.el('div', { class: 'ws-poster-empty' }, ['Chưa có poster']));
                    }
                }
                renderPreview();
                grid.appendChild(preview);

                // Controls
                var controls = hub.el('div', { class: 'ws-poster-controls' });
                var fileInput = hub.el('input', {
                    type: 'file', accept: 'image/jpeg,image/jpg,image/png,image/webp',
                    id: 'moviePosterFile', name: 'moviePosterFile'
                });
                controls.appendChild(hub.el('label', { for: 'moviePosterFile', class: 'ws-btn secondary' }, ['Chọn ảnh mới']));
                controls.appendChild(fileInput);

                var hint = hub.el('p', { class: 'ws-field-hint' },
                    ['Hỗ trợ JPEG/PNG/WEBP, tối đa 5MB. Ảnh sẽ được upload lên Cloudinary.']);
                controls.appendChild(hint);

                // Status line
                var status = hub.el('div', { class: 'ws-poster-status', id: 'moviePosterStatus' });
                controls.appendChild(status);

                var hidden = hub.el('input', {
                    type: 'hidden', id: 'fld-posterUrl', name: 'posterUrl',
                    value: posterState.currentUrl || ''
                });
                controls.appendChild(hidden);

                // Upload-on-change handler: tải file lên Cloudinary, cập nhật hidden posterUrl.
                fileInput.addEventListener('change', function () {
                    var f = fileInput.files && fileInput.files[0];
                    if (!f) return;

                    if (f.size > 5 * 1024 * 1024) {
                        status.textContent = 'File vượt quá 5MB.';
                        status.className = 'ws-poster-status error';
                        fileInput.value = '';
                        return;
                    }

                    status.textContent = 'Đang upload lên Cloudinary...';
                    status.className = 'ws-poster-status uploading';
                    var oldUrl = posterState.currentUrl;
                    hub.uploadFile(f, 'file').then(function (data) {
                        posterState.currentUrl = data.url || '';
                        hidden.value = posterState.currentUrl;
                        posterState.pendingFile = f.name;
                        renderPreview();
                        status.textContent = 'Đã upload: ' + (data.filename || f.name);
                        status.className = 'ws-poster-status success';
                    }).catch(function (err) {
                        status.textContent = 'Upload thất bại: ' + (err.message || 'lỗi không xác định') + '. Giữ nguyên ảnh cũ.';
                        status.className = 'ws-poster-status error';
                        posterState.currentUrl = oldUrl;
                        hidden.value = oldUrl;
                        fileInput.value = '';
                        renderPreview();
                    });
                });

                grid.appendChild(controls);
                wrap.appendChild(grid);

                // Reference hidden input in formData so onSubmit can pick it up.
                // (Already name="posterUrl" so collectData() will include it.)

                formEl.appendChild(wrap);
            },
            onSubmit: function (data) {
                // posterUrl đã được đồng bộ với hidden input ở trên.
                // Nếu user không upload ảnh mới, posterUrl giữ nguyên giá trị ban đầu (movie.posterUrl cũ).
                var url = isEdit ? '/movie/' + movie.id : '/movie';
                var method = isEdit ? 'PUT' : 'POST';
                hub.api(url, { method: method, body: data })
                    .then(function () { router.go('movie'); })
                    .catch(notifyError);
            },
            onCancel: function () { router.go('movie'); }
        });
    }

    router.register('movie', {
        list: function () {
            list.render({
                title: 'Phim',
                subtitle: 'Danh mục phim trong hệ thống và trạng thái phát hành.',
                addUrl: ctx + '/console?module=movie&action=create',
                addLabel: 'Thêm phim',
                searchPlaceholder: 'Tìm theo tên phim…',
                pageSize: 20,
                filters: [
                    { name: 'status', label: 'Trạng thái', options: [
                        { value: 'DRAFT', label: 'Bản nháp' },
                        { value: 'PUBLISHED', label: 'Đang phát hành' },
                        { value: 'ARCHIVED', label: 'Đã lưu trữ' }
                    ]},
                    { name: 'rating', label: 'Giới hạn tuổi', options: [
                        { value: 'P', label: 'P - Mọi lứa tuổi' },
                        { value: 'C13', label: 'C13 - Trên 13 tuổi' },
                        { value: 'C16', label: 'C16 - Trên 16 tuổi' },
                        { value: 'C18', label: 'C18 - Trên 18 tuổi' }
                    ]}
                ],
                sortable: true,
                sortOptions: [
                    { value: 'title', label: 'Tên A→Z' },
                    { value: 'releaseDate', label: 'Ngày phát hành' }
                ],
                fetcher: function (qs) { return hub.api('/movie/search?' + qs.toString()); },
                emptyTitle: 'Chưa có phim',
                emptyMessage: 'Danh mục phim đang trống. Hãy thêm phim đầu tiên.',
                columns: [
                    { label: 'Poster', render: function (m) {
                        if (!m.posterUrl) return document.createTextNode('—');
                        var img = document.createElement('img');
                        img.src = m.posterUrl;
                        img.alt = m.title || '';
                        // Lazy + referrerpolicy giúp Cloudinary không bị hotlink-protected;
                        // onerror fallback tự ẩn cell khi URL chết — không để gãy layout.
                        img.loading = 'lazy';
                        img.referrerPolicy = 'no-referrer';
                        img.crossOrigin = 'anonymous';
                        img.style.cssText = 'width:48px;height:64px;object-fit:cover;border-radius:6px;display:block;background:#f3f4f6';
                        img.addEventListener('error', function () {
                            img.replaceWith(document.createTextNode('—'));
                        });
                        return img;
                    }, width: '70px' },
                    { label: 'Tên phim', key: 'title' },
                    { label: 'Thể loại', key: 'genre' },
                    { label: 'Thời lượng', render: function (m) { return m.durationMin ? (m.durationMin + ' phút') : '—'; } },
                    { label: 'Giới hạn tuổi', key: 'rating' },
                    { label: 'Trạng thái', render: function (m) { return statusBadge(m.status); } }
                ],
                actions: function (row) {
                    var arr = [{ label: 'Sửa', class: 'secondary', href: ctx + '/console?module=movie&action=edit&id=' + row.id }];
                    if (row.status === 'DRAFT') {
                        arr.push({ label: 'Phát hành', class: 'primary', onClick: function () {
                            confirmAction('Phát hành phim này?', function () {
                                hub.api('/movie/' + row.id, { method: 'PUT', body: { action: 'publish' } })
                                    .then(function () {
                                        hub.notify('Đã phát hành phim.', 'ok');
                                        router.go('movie');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    } else if (row.status === 'PUBLISHED') {
                        arr.push({ label: 'Lưu trữ', class: 'danger', onClick: function () {
                            confirmAction('Lưu trữ phim này?', function () {
                                hub.api('/movie/' + row.id, { method: 'PUT', body: { action: 'archive' } })
                                    .then(function () {
                                        hub.notify('Đã lưu trữ phim.', 'ok');
                                        router.go('movie');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        create: function () { renderMovieForm(null); },
        edit: function (id) {
            hub.api('/movie')
                .then(function (list) {
                    var movie = (list || []).find(function (m) { return String(m.id) === String(id); });
                    if (!movie) throw new Error('Không tìm thấy phim');
                    renderMovieForm(movie);
                })
                .catch(notifyError);
        }
    });

    // ================================================================
    //  SCREEN (Admin + Manager)
    // ================================================================
    /**
     * Render chọn ghế VIP với 3 cơ chế:
     *  - Click ghế = toggle VIP/Standard (nhanh cho 1 ghế).
     *  - Click nhãn hàng A/B/C... = set cả hàng thành VIP.
     *  - Kéo chuột (drag) trên nhiều ghế = chọn cả vùng.
     *    + Nếu bắt đầu kéo trên ghế thường → cả vùng thành VIP.
     *    + Nếu bắt đầu kéo trên ghế VIP → cả vùng thành ghế thường.
     *    + Giữ phím Alt khi kéo = toggle ngược lại từng ghế trong vùng.
     *
     * Trải nghiệm giống các tool thiết kế phòng chiếu: chọn vùng nhanh,
     * không cần click từng ghế một.
     */
    function renderSeatPicker(container, vipInputOrRows, rowCount, colCount) {
        // Alias helper `el`: renderSeatPicker ban đầu được viết phụ thuộc vào helper el(),
        // nhưng `el` không có sẵn trong scope — gây ReferenceError khi re-render từ rowCount/colCount.
        // Lấy từ CinemaHub (đã được đăng ký trong app.js) hoặc fallback về createElement.bind(document).
        var el = (hub && hub.el) || (function () {
            return function (tag, attrs, children) {
                var node = document.createElement(tag);
                if (attrs) {
                    Object.keys(attrs).forEach(function (k) {
                        if (k === 'class') node.className = attrs[k];
                        else if (k === 'text') node.textContent = attrs[k];
                        else node.setAttribute(k, attrs[k]);
                    });
                }
                if (children) {
                    (Array.isArray(children) ? children : [children]).forEach(function (c) {
                        if (c == null) return;
                        node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
                    });
                }
                return node;
            };
        })();

        container.innerHTML = '';

        // Hỗ trợ 2 kiểu tham số để không phá code cũ:
        //  - Nếu caller truyền <input type=hidden> thì bind trực tiếp vào đó.
        //  - Nếu truyền chuỗi vipRows thì tự tạo hidden input mới (chỉ dùng cho
        //    smoke test / debug).
        var hiddenVip;
        var initialVipRows;
        if (vipInputOrRows && vipInputOrRows.tagName === 'INPUT') {
            hiddenVip = vipInputOrRows;
            initialVipRows = hiddenVip.value || '';
        } else {
            initialVipRows = vipInputOrRows || '';
            hiddenVip = document.createElement('input');
            hiddenVip.type = 'hidden';
            hiddenVip.name = 'vipRows';
            hiddenVip.value = initialVipRows;
            container.appendChild(hiddenVip);
        }

        var vipRowSet = new Set();
        if (initialVipRows) {
            initialVipRows.split(/[,;\s]+/).forEach(function (s) {
                var t = s.trim().toUpperCase();
                if (t) vipRowSet.add(t);
            });
        }

        // Toolbar với legend phím tắt
        var toolbar = el('div', { class: 'seat-picker-toolbar' });
        toolbar.appendChild(el('span', { class: 'seat-picker-label' }, ['Sơ đồ ghế']));
        toolbar.appendChild(el('span', { class: 'seat-picker-hint' },
            ['Click ghế: toggle. Kéo chuột: chọn vùng. Click nhãn hàng: chọn cả hàng.']));
        container.appendChild(toolbar);

        // Mode indicator (Vip/Standard) — phản ánh mode hiện tại khi đang kéo
        var modeBadge = el('span', { class: 'seat-picker-mode-badge', 'aria-live': 'polite' });

        var scrollWrap = el('div', { class: 'seat-picker-scroll' });
        var screen = el('div', { class: 'seat-picker-screen' });
        screen.appendChild(el('div', { class: 'seat-picker-curtain' }, ['— Màn hình —']));
        var grid = el('div', { class: 'seat-grid' });

        // --------- Trạng thái cho drag-select ---------
        // dragging: đang kéo hay không
        // dragMode: 'add-vip' (set VIP) hoặc 'remove-vip' (set thường) — dựa trên
        //   trạng thái GỐC (chưa apply) của ghế đầu tiên user pointer-down vào.
        // originalStates: Map<seatBtn, boolean> lưu VIP/GỐC của từng ghế đã chạm
        //   trong lần kéo — để click đơn (size=1) biết phải toggle về phía ngược lại.
        var dragging = false;
        var dragMode = null;
        var originalStates = new Map(); // btn -> bool (wasVip trước khi apply)
        var dragHandled = new Set();

        var rowCells = {};
        for (var i = 0; i < rowCount; i++) {
            var rowLabel = String.fromCharCode(65 + i);
            var row = el('div', { class: 'seat-row' });
            var rowLabelBtn = el('button', {
                type: 'button',
                class: 'seat-row-label',
                title: 'Bấm để chọn cả hàng ' + rowLabel + ' là ghế VIP'
            }, [rowLabel]);
            rowLabelBtn.addEventListener('click', (function (capRow) {
                return function () {
                    var nodes = rowCells[capRow] || [];
                    var anyNotVip = nodes.some(function (n) { return !n.classList.contains('is-vip'); });
                    nodes.forEach(function (n) {
                        if (anyNotVip) n.classList.add('is-vip');
                        else n.classList.remove('is-vip');
                    });
                    updateSummary();
                };
            })(rowLabel));
            row.appendChild(rowLabelBtn);

            rowCells[rowLabel] = [];
            for (var c = 1; c <= colCount; c++) {
                var isVip = vipRowSet.has(rowLabel);
                var seatBtn = el('button', {
                    type: 'button',
                    class: 'seat-cell' + (isVip ? ' is-vip' : ''),
                    'data-row': rowLabel,
                    'data-col': String(c),
                    'aria-label': rowLabel + c + (isVip ? ' (VIP)' : '')
                }, [String(c)]);

                // ---- Drag-select với pointer events ----
                // pointerdown: bắt đầu theo dõi, lưu trạng thái GỐC của ghế,
                //   set mode dựa trên trạng thái gốc (click vào ghế VIP sẽ
                //   đi theo hướng 'remove-vip' — kéo để bỏ chọn vùng VIP).
                seatBtn.addEventListener('pointerdown', (function (btn) {
                    return function (ev) {
                        if (ev.button !== undefined && ev.button !== 0) return;
                        dragging = true;
                        // Lưu trạng thái GỐC trước khi apply.
                        originalStates.set(btn, btn.classList.contains('is-vip'));
                        dragMode = originalStates.get(btn) ? 'remove-vip' : 'add-vip';
                        dragHandled = new Set();
                        ev.preventDefault();
                        try { btn.setPointerCapture(ev.pointerId); } catch (_) {}
                        // KHÔNG apply ngay tại đây — đợi pointerenter/pointerup để
                        //   biết đây là click đơn (toggle) hay kéo (set theo mode).
                        updateSummary();
                    };
                })(seatBtn));

                seatBtn.addEventListener('pointerenter', (function (btn) {
                    return function (ev) {
                        if (!dragging) return;
                        if (!originalStates.has(btn)) {
                            originalStates.set(btn, btn.classList.contains('is-vip'));
                        }
                        if (dragHandled.has(btn)) return;
                        dragHandled.add(btn);
                        applyDragMode(btn);
                        updateSummary();
                    };
                })(seatBtn));

                seatBtn.addEventListener('pointerup', (function (btn) {
                    return function () {
                        if (!dragging) return;
                        // Click đơn (dragHandled chỉ có 1 phần tử, đúng btn) → toggle.
                        // Kéo vùng (size > 1) → giữ nguyên mode đã apply.
                        if (dragHandled.size <= 1 && originalStates.has(btn)) {
                            var wasVip = originalStates.get(btn);
                            if (wasVip) btn.classList.remove('is-vip');
                            else btn.classList.add('is-vip');
                            updateSummary();
                        }
                        endDrag();
                    };
                })(seatBtn));

                // Khi chuột rời grid cũng kết thúc drag
                seatBtn.addEventListener('pointercancel', function () { endDrag(); });

                row.appendChild(seatBtn);
                rowCells[rowLabel].push(seatBtn);
            }
            grid.appendChild(row);
        }
        screen.appendChild(grid);
        scrollWrap.appendChild(screen);
        container.appendChild(scrollWrap);

        // Kết thúc kéo khi thả chuột ở bất cứ đâu (kể cả ngoài grid) — áp dụng
        // toggle cho click đơn vào bất kỳ ghế nào (kể cả khi up ngoài grid thì
        // đã có pointerup riêng trên ghế down xử lý rồi).
        document.addEventListener('pointerup', endDrag);
        document.addEventListener('pointercancel', endDrag);

        function applyDragMode(btn) {
            // Mode dựa trên trạng thái GỐC của ghế đó (đã lưu trong originalStates),
            //   không phải trạng thái hiện tại — để kéo qua vùng đã có sẵn VIP/standard
            //   vẫn cho ra kết quả đúng theo ý user (kéo từ 1 ghế chưa VIP sang
            //   1 vùng đã VIP thì cả vùng thành VIP).
            var wasVip = originalStates.get(btn);
            if (wasVip === undefined) {
                wasVip = btn.classList.contains('is-vip');
                originalStates.set(btn, wasVip);
            }
            if (wasVip) btn.classList.remove('is-vip');
            else btn.classList.add('is-vip');
        }

        function endDrag() {
            if (!dragging) return;
            dragging = false;
            dragMode = null;
            dragHandled = new Set();
            originalStates = new Map();
        }

        // Legend
        var legend = el('div', { class: 'seat-picker-legend' });
        legend.appendChild(el('div', { class: 'legend-item' }, [
            el('span', { class: 'legend-swatch seat-cell' }, []),
            el('span', {}, ['Ghế thường'])
        ]));
        legend.appendChild(el('div', { class: 'legend-item' }, [
            el('span', { class: 'legend-swatch seat-cell is-vip' }, []),
            el('span', {}, ['Ghế VIP (giá cao hơn)'])
        ]));
        legend.appendChild(modeBadge);
        container.appendChild(legend);

        // Summary bar
        var summary = el('div', { class: 'seat-picker-summary' });
        var summaryText = el('span', { class: 'seat-picker-text' }, []);
        var resetAllBtn = el('button', {
            type: 'button',
            class: 'ws-btn secondary small'
        }, ['Bỏ chọn tất cả VIP']);
        resetAllBtn.addEventListener('click', function () {
            container.querySelectorAll('.seat-cell.is-vip').forEach(function (b) {
                b.classList.remove('is-vip');
            });
            updateSummary();
        });
        summary.appendChild(summaryText);
        summary.appendChild(resetAllBtn);
        container.appendChild(summary);

        function updateSummary() {
            var vipButtons = container.querySelectorAll('.seat-cell.is-vip');
            var uniqueRows = new Set();
            vipButtons.forEach(function (b) { uniqueRows.add(b.getAttribute('data-row')); });
            var rows = Array.from(uniqueRows).sort();
            hiddenVip.value = rows.join(',');
            summaryText.textContent = vipButtons.length === 0
                ? 'Chưa chọn ghế VIP nào.'
                : 'Đã chọn ' + vipButtons.length + ' ghế VIP thuộc hàng: ' + (rows.join(', ') || '—');
            // Cập nhật mode badge cho thấy action sẽ xảy ra khi click/kéo
            if (dragging) {
                modeBadge.textContent = dragMode === 'add-vip'
                    ? 'Đang chọn: thành VIP'
                    : 'Đang chọn: thành ghế thường';
                modeBadge.className = 'seat-picker-mode-badge is-active ' +
                    (dragMode === 'add-vip' ? 'is-vip-mode' : 'is-standard-mode');
            } else {
                modeBadge.textContent = '';
                modeBadge.className = 'seat-picker-mode-badge';
            }
        }
        updateSummary();
    }

    function renderScreenForm(screen, branches, preselectedBranchId) {
        var isEdit = !!screen;
        var fields = [
            isEdit ? null : { name: 'branchId', label: 'Chi nhánh', type: 'select', required: true, value: preselectedBranchId, options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
            { name: 'code', label: 'Mã phòng', required: true, value: screen && screen.code, placeholder: 'VD: R01' },
            { name: 'name', label: 'Tên phòng', required: true, value: screen && screen.name, placeholder: 'Phòng chiếu 1' },
            { name: 'rowCount', label: 'Số hàng', type: 'number', required: true, min: 1, max: 26, value: (screen && screen.rowCount) || 8 },
            { name: 'colCount', label: 'Số cột', type: 'number', required: true, min: 1, max: 30, value: (screen && screen.colCount) || 12 }
        ].filter(Boolean);

        var initialRowCount = Number(((screen && screen.rowCount) || 8));
        var initialColCount = Number(((screen && screen.colCount) || 12));

        // Render form bằng renderFormPage, sau đó append seat picker theo row/col động.
        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Phòng chiếu', href: ctx + '/console?module=screen' }, isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa phòng chiếu' : 'Tạo phòng chiếu mới',
            subtitle: isEdit ? screen.name : 'Thêm phòng chiếu cho chi nhánh.',
            submitLabel: isEdit ? 'Lưu thay đổi' : 'Tạo phòng',
            sections: [{
                title: 'Thông tin phòng',
                fields: fields
            }],
            // Bỏ phần "vipRows" cũ ra khỏi sections, ta nhúng vào renderFooter bên dưới.
            renderFooter: function (formEl) {
                // Section sơ đồ ghế VIP
                var section = document.createElement('section');
                section.className = 'ws-form-section';
                var heading = document.createElement('h2');
                heading.className = 'ws-form-section-title';
                heading.textContent = 'Sơ đồ ghế & chọn ghế VIP';
                section.appendChild(heading);
                var desc = document.createElement('p');
                desc.className = 'ws-form-section-desc';
                desc.textContent = 'Cấu hình số hàng × số cột ở trên, sau đó click trực tiếp trên sơ đồ ghế để chọn ghế VIP. Click nhãn hàng (A, B, C…) để chọn cả hàng.';
                section.appendChild(desc);

                var seatHost = document.createElement('div');
                seatHost.id = 'seat-picker-host';
                seatHost.className = 'seat-picker-host';
                section.appendChild(seatHost);

                formEl.appendChild(section);

                // CHỈ tạo 1 hidden input duy nhất cho vipRows. Picker tham chiếu trực
                // tiếp vào input này để submit form luôn mang theo giá trị mới nhất —
                // trước đây có 2 hidden trùng name khiến FormData ghi đè lung tung và
                // hidden gốc trong formEl không được cập nhật khi user chọn ghế.
                var hidden = document.createElement('input');
                hidden.type = 'hidden';
                hidden.name = 'vipRows';
                hidden.value = (screen && screen.vipRows) || '';
                formEl.appendChild(hidden);

                // State cho picker — dùng closure để re-render khi đổi rowCount/colCount.
                var currentRows = initialRowCount;
                var currentCols = initialColCount;
                var initialVipRows = (screen && screen.vipRows) || '';

                function redrawSeatPicker() {
                    var rowInput = formEl.querySelector('input[name="rowCount"]');
                    var colInput = formEl.querySelector('input[name="colCount"]');
                    if (!rowInput || !colInput) return;
                    currentRows = Math.max(1, Math.min(26, parseInt(rowInput.value, 10) || 1));
                    currentCols = Math.max(1, Math.min(30, parseInt(colInput.value, 10) || 1));
                    // Truyền hidden vào picker để picker bind trực tiếp — không tự tạo
                    // hidden riêng để tránh duplicate.
                    renderSeatPicker(seatHost, hidden, currentRows, currentCols);
                }

                // Cần đợi input rowCount/colCount được render xong mới bind.
                setTimeout(redrawSeatPicker, 0);
                var rowInput = formEl.querySelector('input[name="rowCount"]');
                var colInput = formEl.querySelector('input[name="colCount"]');
                if (rowInput) rowInput.addEventListener('change', redrawSeatPicker);
                if (colInput) colInput.addEventListener('change', redrawSeatPicker);
            },
            onSubmit: function (data) {
                var url = isEdit ? '/screen/' + screen.id : '/screen';
                var method = isEdit ? 'PUT' : 'POST';
                // Seat picker cập nhật hidden vipRows rồi — data đã mang theo giá trị mới nhất.
                hub.api(url, { method: method, body: data })
                    .then(function () { router.go('screen'); })
                    .catch(notifyError);
            },
            onCancel: function () { router.go('screen'); }
        });
    }

    router.register('screen', {
        list: function () {
            hub.api('/branch').then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                var branchId = params.get('branchId') || (branches[0] && branches[0].id);
                list.render({
                    title: 'Phòng chiếu & sơ đồ ghế',
                    subtitle: 'Quản lý phòng chiếu và sơ đồ ghế cho từng chi nhánh.',
                    addUrl: branchId ? (ctx + '/console?module=screen&action=create&branchId=' + branchId) : null,
                    addLabel: 'Tạo phòng chiếu',
                    pageSize: 20,
                    filters: [{
                        name: 'branchId', label: 'Chi nhánh',
                        initialValue: branchId ? String(branchId) : '',
                        options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; })
                    }],
                    fetcher: function (qs) {
                        if (!qs.get('branchId')) {
                            return Promise.resolve({ items: [], total: 0 });
                        }
                        var url = '/screen?branchId=' + encodeURIComponent(qs.get('branchId'));
                        return hub.api(url).then(function (list) {
                            return { items: Array.isArray(list) ? list : [], total: (list || []).length };
                        });
                    },
                    emptyTitle: 'Chưa có phòng chiếu',
                    emptyMessage: 'Chi nhánh này chưa có phòng chiếu nào.',
                    columns: [
                        { label: 'Mã', key: 'code' },
                        { label: 'Tên', key: 'name' },
                        { label: 'Số hàng', key: 'rowCount' },
                        { label: 'Số cột', key: 'colCount' },
                        { label: 'Ghế VIP', key: 'vipRows' },
                        { label: 'Trạng thái', render: function (s) { return statusBadge(s.status); } }
                    ],
                    actions: function (row) {
                        var arr = [{ label: 'Sửa', class: 'secondary', href: ctx + '/console?module=screen&action=edit&id=' + row.id }];
                        if (row.status === 'ACTIVE') {
                            arr.push({ label: 'Ngưng', class: 'danger', onClick: function () {
                                confirmAction('Ngưng hoạt động phòng chiếu?', function () {
                                    hub.api('/screen/' + row.id, { method: 'PUT', body: { action: 'deactivate' } })
                                        .then(function () {
                                            hub.notify('Đã ngưng phòng chiếu.', 'ok');
                                            router.go('screen');
                                        })
                                        .catch(notifyError);
                                });
                            }});
                        }
                        return arr;
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            hub.api('/branch').then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                var branchId = params.get('branchId') || (branches[0] && branches[0].id);
                renderScreenForm(null, branches, branchId);
            }).catch(notifyError);
        },
        edit: function (id) {
            var branches = null;
            hub.api('/branch')
                .then(function (b) {
                    branches = Array.isArray(b) ? b : [];
                    if (!branches.length) throw new Error('Chưa có chi nhánh để tải phòng chiếu');
                    return Promise.all(branches.map(function (branch) {
                        return hub.api('/screen?branchId=' + encodeURIComponent(branch.id))
                            .then(function (screens) {
                                return Array.isArray(screens) ? screens : [];
                            });
                    }));
                })
                .then(function (screenLists) {
                    var screens = [];
                    screenLists.forEach(function (items) {
                        screens = screens.concat(items);
                    });
                    var screen = screens.find(function (s) { return String(s.id) === String(id); });
                    if (!screen) throw new Error('Không tìm thấy phòng chiếu');
                    renderScreenForm(screen, branches, screen.branchId);
                })
                .catch(notifyError);
        }
    });

    // ================================================================
    //  SHOWTIME (Admin + Manager)
    // ================================================================
    router.register('showtime', {
        list: function () {
            Promise.all([
                hub.api('/branch').catch(function () { return []; }),
                hub.api('/movie').catch(function () { return []; })
            ]).then(function (results) {
                var branches = results[0] || [];
                var movies = results[1] || [];
                list.render({
                    title: 'Suất chiếu',
                    subtitle: 'Quản lý lịch chiếu phim theo chi nhánh và phòng.',
                    addUrl: ctx + '/console?module=showtime&action=create',
                    addLabel: 'Tạo suất chiếu',
                    pageSize: 20,
                    filters: [
                        { name: 'branchId', label: 'Chi nhánh',
                          options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
                        { name: 'movieId', label: 'Phim',
                          options: (movies || []).map(function (m) { return { value: String(m.id), label: m.title }; }) },
                        { name: 'status', label: 'Trạng thái', options: [
                            { value: 'OPEN', label: 'Mở bán' },
                            { value: 'ENDED', label: 'Đã kết thúc' },
                            { value: 'CANCELLED', label: 'Đã hủy' }
                        ]}
                    ],
                    sortable: true,
                    sortOptions: [
                        { value: 'startTime', label: 'Giờ bắt đầu' },
                        { value: 'createdAt', label: 'Mới nhất' }
                    ],
                    fetcher: function (qs) { return hub.api('/showtime/manage/search?' + qs.toString()); },
                    emptyTitle: 'Chưa có suất chiếu',
                    emptyMessage: 'Hãy tạo suất chiếu đầu tiên cho chi nhánh.',
                    columns: [
                        { label: 'ID', key: 'id', width: '60px' },
                        { label: 'Phim', key: 'movieTitle' },
                        { label: 'Chi nhánh', key: 'branchName' },
                        { label: 'Phòng', key: 'screenName' },
                        { label: 'Bắt đầu', render: function (s) { return s.startTime ? hub.fmtDateTime(s.startTime) : '—'; } },
                        { label: 'Kết thúc', render: function (s) { return s.endTime ? hub.fmtDateTime(s.endTime) : '—'; } },
                        { label: 'Trạng thái', render: function (s) { return statusBadge(s.status); } }
                    ],
                    actions: function (row) {
                        if (row.status !== 'OPEN') return [];
                        return [{ label: 'Hủy', class: 'danger', onClick: function () {
                            confirmAction('Hủy suất chiếu này?', function () {
                                hub.api('/showtime/' + row.id, { method: 'PUT', body: { action: 'cancel' } })
                                    .then(function () {
                                        hub.notify('Đã hủy suất chiếu.', 'ok');
                                        router.go('showtime');
                                    })
                                    .catch(notifyError);
                            });
                        }}];
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            Promise.all([
                hub.api('/branch').catch(function () { return []; }),
                hub.api('/movie').catch(function () { return []; }),
                hub.api('/screen').catch(function () { return []; })
            ]).then(function (results) {
                var branches = results[0] || [];
                var movies = results[1] || [];
                var screens = results[2] || [];
                form.renderFormPage({
                    breadcrumb: ['Quản lý', { label: 'Suất chiếu', href: ctx + '/console?module=showtime' }, 'Tạo mới'],
                    title: 'Tạo suất chiếu mới',
                    subtitle: 'Lên lịch chiếu phim cho phòng và khung giờ cụ thể.',
                    submitLabel: 'Tạo suất chiếu',
                    sections: [{
                        title: 'Thông tin suất chiếu',
                        fields: [
                            { name: 'branchId', label: 'Chi nhánh', type: 'select', required: true,
                              options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }),
                              hint: 'Lọc lại phòng chiếu sau khi chọn chi nhánh.' },
                            { name: 'movieId', label: 'Phim', type: 'select', required: true,
                              options: (movies || []).filter(function (m) { return m.status === 'PUBLISHED'; }).map(function (m) {
                                  return { value: String(m.id), label: m.title + ' (' + (m.durationMin || '?') + 'p)' };
                              }) },
                            { name: 'screenId', label: 'Phòng chiếu', type: 'select', required: true,
                              options: (screens || []).map(function (s) { return { value: String(s.id), label: (s.name || 'Phòng #' + s.id) }; }) },
                            { name: 'startTime', label: 'Giờ bắt đầu', type: 'datetime-local', required: true,
                              hint: 'Định dạng: yyyy-MM-ddTHH:mm. Suất chiếu sẽ tự tính giờ kết thúc theo thời lượng phim.' },
                            { name: 'cleaningBufferMin', label: 'Thời gian vệ sinh giữa ca (phút)', type: 'number', min: 0, max: 60, value: 15,
                              hint: 'Khoảng đệm giữa suất chiếu này và suất kế tiếp.' }
                        ]
                    }],
                    // Khi đổi chi nhánh → refetch /screen?branchId=X và repopulate dropdown "Phòng chiếu".
                    renderFooter: function (formEl) {
                        var branchSelect = formEl.querySelector('select[name="branchId"]');
                        var screenSelect = formEl.querySelector('select[name="screenId"]');
                        if (!branchSelect || !screenSelect) return;

                        function populateScreens(list) {
                            // Lưu giá trị đang chọn (nếu còn tồn tại trong list mới)
                            var prev = screenSelect.value;
                            screenSelect.innerHTML = '';
                            var placeholder = document.createElement('option');
                            placeholder.value = '';
                            placeholder.textContent = (list && list.length)
                                ? '— Chọn phòng chiếu —'
                                : '— Chi nhánh này chưa có phòng chiếu —';
                            screenSelect.appendChild(placeholder);
                            (list || []).forEach(function (s) {
                                var opt = document.createElement('option');
                                opt.value = String(s.id);
                                opt.textContent = s.name || ('Phòng #' + s.id);
                                screenSelect.appendChild(opt);
                            });
                            // Khôi phục lựa chọn nếu vẫn hợp lệ, nếu không thì reset về rỗng.
                            if (prev && (list || []).some(function (s) { return String(s.id) === prev; })) {
                                screenSelect.value = prev;
                            } else {
                                screenSelect.value = '';
                            }
                        }

                        function loadScreensForBranch(branchId) {
                            if (!branchId) {
                                populateScreens([]);
                                return;
                            }
                            hub.api('/screen?branchId=' + encodeURIComponent(branchId))
                                .then(function (list) { populateScreens(Array.isArray(list) ? list : []); })
                                .catch(function () { populateScreens([]); });
                        }

                        branchSelect.addEventListener('change', function () {
                            loadScreensForBranch(branchSelect.value);
                        });

                        // Khởi tạo dropdown theo branchId đang được chọn (nếu có)
                        loadScreensForBranch(branchSelect.value);
                    },
                    onSubmit: function (data) {
                        hub.api('/showtime', { method: 'POST', body: data })
                            .then(function () { router.go('showtime'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('showtime'); }
                });
            }).catch(notifyError);
        },
        edit: function (id) {
            hub.api('/showtime/' + id).then(function (showtime) {
                form.renderFormPage({
                    breadcrumb: ['Quản lý', { label: 'Suất chiếu', href: ctx + '/console?module=showtime' }, 'Chỉnh sửa'],
                    title: 'Chỉnh sửa suất chiếu',
                    subtitle: showtime.movieTitle + ' — ' + showtime.branchName,
                    submitLabel: 'Lưu thay đổi',
                    sections: [{
                        title: 'Thông tin suất chiếu',
                        fields: [
                            { name: 'startTime', label: 'Giờ bắt đầu', type: 'datetime-local', required: true,
                              value: showtime.startTime ? showtime.startTime.replace(' ', 'T').substring(0, 16) : '' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/showtime/' + id, { method: 'PUT', body: data })
                            .then(function () { router.go('showtime'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('showtime'); }
                });
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  PRICING (Admin only)
    // ================================================================
    router.register('pricing', {
        list: function () {
            list.render({
                title: 'Bảng giá vé',
                subtitle: 'Khung giá theo loại ghế, ngày và khung giờ.',
                addUrl: ctx + '/console?module=pricing&action=create',
                addLabel: 'Tạo khung giá',
                pageSize: 50,
                sortable: true,
                sortOptions: [
                    { value: 'seatType', label: 'Loại ghế' },
                    { value: 'price', label: 'Giá tăng dần' },
                    { value: 'price:desc', label: 'Giá giảm dần' }
                ],
                fetcher: function () {
                    return hub.api('/pricing/rules').then(function (list) {
                        return { items: list || [], total: (list || []).length };
                    });
                },
                emptyTitle: 'Chưa có khung giá',
                emptyMessage: 'Hãy tạo khung giá đầu tiên cho hệ thống.',
                columns: [
                    { label: 'ID', key: 'id', width: '60px' },
                    { label: 'Loại ghế', key: 'seatType' },
                    { label: 'Ngày', key: 'dayType' },
                    { label: 'Khung giờ', key: 'timeSlot' },
                    { label: 'Giá vé', render: function (r) { return hub.fmtMoney(r.price); } }
                ],
                actions: function (row) {
                    return [
                        { label: 'Sửa giá', class: 'secondary', href: ctx + '/console?module=pricing&action=edit&id=' + row.id },
                        { label: 'Xóa', class: 'danger', onClick: function () {
                            confirmAction('Xóa khung giá này? (Vé đã bán giữ nguyên giá snapshot)', function () {
                                hub.api('/pricing/rules/' + row.id, { method: 'DELETE' })
                                    .then(function () {
                                        hub.notify('Đã xóa khung giá.', 'ok');
                                        router.go('pricing');
                                    })
                                    .catch(notifyError);
                            });
                        }}
                    ];
                }
            });
        },
        create: function () {
            form.renderFormPage({
                breadcrumb: ['Quản lý', { label: 'Bảng giá', href: ctx + '/console?module=pricing' }, 'Tạo mới'],
                title: 'Tạo khung giá mới',
                subtitle: 'Thiết lập giá vé cho một tổ hợp loại ghế + loại ngày + khung giờ.',
                submitLabel: 'Tạo khung giá',
                sections: [{
                    title: 'Chi tiết khung giá',
                    fields: [
                        { name: 'seatType', label: 'Loại ghế', type: 'select', required: true, options: [
                            { value: 'STANDARD', label: 'Ghế thường' },
                            { value: 'VIP', label: 'Ghế VIP' },
                            { value: 'COUPLE', label: 'Ghế đôi' }
                        ]},
                        { name: 'dayType', label: 'Loại ngày', type: 'select', required: true, options: [
                            { value: 'WEEKDAY', label: 'Ngày thường' },
                            { value: 'WEEKEND', label: 'Cuối tuần' },
                            { value: 'HOLIDAY', label: 'Ngày lễ' }
                        ]},
                        { name: 'timeSlot', label: 'Khung giờ', type: 'select', required: true, options: [
                            { value: 'EARLY', label: 'Sáng sớm (<12h)' },
                            { value: 'DAY', label: 'Ban ngày (12-18h)' },
                            { value: 'LATE', label: 'Tối (>18h)' }
                        ]},
                        { name: 'price', label: 'Giá vé (VND)', type: 'number', required: true, min: 1000, step: 1000 }
                    ]
                }],
                onSubmit: function (data) {
                    hub.api('/pricing/rules', { method: 'POST', body: data })
                        .then(function () { router.go('pricing'); })
                        .catch(notifyError);
                },
                onCancel: function () { router.go('pricing'); }
            });
        },
        edit: function (id) {
            hub.api('/pricing/rules').then(function (list) {
                var rule = (list || []).find(function (r) { return String(r.id) === String(id); });
                if (!rule) throw new Error('Không tìm thấy khung giá');
                form.renderFormPage({
                    breadcrumb: ['Quản lý', { label: 'Bảng giá', href: ctx + '/console?module=pricing' }, 'Chỉnh sửa'],
                    title: 'Chỉnh sửa khung giá',
                    subtitle: rule.seatType + ' · ' + rule.dayType + ' · ' + rule.timeSlot,
                    submitLabel: 'Lưu giá mới',
                    sections: [{
                        title: 'Chi tiết khung giá',
                        fields: [
                            { name: 'price', label: 'Giá vé (VND)', type: 'number', required: true, min: 1000, step: 1000, value: rule.price }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/pricing/rules/' + id, { method: 'PUT', body: data })
                            .then(function () { router.go('pricing'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('pricing'); }
                });
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  INVENTORY (Admin + Manager)
    // ================================================================
    router.register('inventory', {
        list: function () {
            hub.api('/branch').then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                var branchId = params.get('branchId') || (branches[0] && branches[0].id);
                list.render({
                    title: 'Kho hàng',
                    subtitle: 'Theo dõi và điều chỉnh tồn kho tại từng chi nhánh.',
                    addUrl: ctx + '/console?module=inventory&action=create&branchId=' + (branchId || ''),
                    addLabel: 'Nhập kho',
                    pageSize: 50,
                    filters: [{
                        name: 'branchId', label: 'Chi nhánh',
                        initialValue: branchId ? String(branchId) : '',
                        options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; })
                    }],
                    fetcher: function (qs) {
                        var url = '/inventory' + (qs.toString() ? '?' + qs.toString() : '');
                        return hub.api(url).then(function (list) {
                            return { items: list || [], total: (list || []).length };
                        });
                    },
                    emptyTitle: 'Chưa có tồn kho',
                    emptyMessage: 'Chi nhánh chưa có sản phẩm nào trong kho.',
                    columns: [
                        { label: 'Sản phẩm', key: 'name' },
                        { label: 'Loại', key: 'type' },
                        { label: 'Số lượng', render: function (s) { return (s.qty || 0) + ' ' + (s.unit || ''); } },
                        { label: 'Giá bán', render: function (s) { return hub.fmtMoney(s.price); } }
                    ],
                    actions: function (row) {
                        return [
                            { label: 'Nhập', class: 'secondary', href: ctx + '/console?module=inventory&action=create&branchId=' + (branchId || '') + '&productId=' + row.productId },
                            { label: 'Điều chỉnh', class: 'danger', href: ctx + '/console?module=inventory&action=edit&branchId=' + (branchId || '') + '&productId=' + row.productId }
                        ];
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            Promise.all([
                hub.api('/branch').catch(function () { return []; }),
                hub.api('/concession/products').catch(function () { return []; })
            ]).then(function (results) {
                var branches = results[0] || [];
                var products = results[1] || [];
                var params = new URLSearchParams(window.location.search);
                var preselectedBranch = params.get('branchId');
                var preselectedProduct = params.get('productId');
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Kho hàng', href: ctx + '/console?module=inventory' }, 'Nhập kho'],
                    title: 'Nhập kho',
                    subtitle: 'Thêm số lượng tồn cho sản phẩm tại chi nhánh.',
                    submitLabel: 'Nhập kho',
                    sections: [{
                        title: 'Thông tin nhập kho',
                        fields: [
                            { name: 'branchId', label: 'Chi nhánh', type: 'select', required: true,
                              value: preselectedBranch,
                              options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
                            { name: 'productId', label: 'Sản phẩm', type: 'select', required: true,
                              value: preselectedProduct,
                              options: (products || []).map(function (p) { return { value: String(p.id), label: p.name }; }) },
                            { name: 'quantity', label: 'Số lượng nhập', type: 'number', required: true, min: 1 },
                            { name: 'reason', label: 'Lý do / Ghi chú', required: true, value: 'Nhập kho',
                              hint: 'Bắt buộc để theo dõi lịch sử tồn kho.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/inventory', { method: 'POST', body: data })
                            .then(function () { router.go('inventory'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('inventory'); }
                });
            }).catch(notifyError);
        },
        edit: function (id) {
            hub.api('/concession/products').then(function (products) {
                var params = new URLSearchParams(window.location.search);
                var branchId = params.get('branchId');
                var productId = params.get('productId') || id;
                var product = (products || []).find(function (p) { return String(p.id) === String(productId); });
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Kho hàng', href: ctx + '/console?module=inventory' }, 'Điều chỉnh'],
                    title: 'Điều chỉnh tồn kho',
                    subtitle: product ? product.name : 'Điều chỉnh chênh lệch tồn kho',
                    submitLabel: 'Lưu điều chỉnh',
                    sections: [{
                        title: 'Thông tin điều chỉnh',
                        description: 'Số dương = thêm, số âm = giảm. Lý do bắt buộc.',
                        fields: [
                            { name: 'branchId', label: 'Chi nhánh', type: 'hidden', required: true, value: branchId },
                            { name: 'productId', label: 'Sản phẩm', type: 'hidden', required: true, value: productId },
                            { name: 'quantity', label: 'Chênh lệch (+ hoặc -)', type: 'number', required: true, step: 1 },
                            { name: 'reason', label: 'Lý do', required: true, value: 'Kiểm kê',
                              hint: 'Tối thiểu 5 ký tự để audit trail.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        data.action = 'adjust';
                        hub.api('/inventory', { method: 'POST', body: data })
                            .then(function () { router.go('inventory'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('inventory'); }
                });
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  SHIFT (Staff + Manager)
    // ================================================================
    router.register('shift', {
        list: function () {
            hub.api('/branch').then(function (branches) {
                list.render({
                    title: 'Ca làm việc',
                    subtitle: 'Lịch sử mở/đóng ca và đối soát tiền mặt.',
                    pageSize: 20,
                    filters: [
                        { name: 'branchId', label: 'Chi nhánh',
                          options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
                        { name: 'status', label: 'Trạng thái', options: [
                            { value: 'OPEN', label: 'Đang mở' },
                            { value: 'CLOSED', label: 'Đã đóng' },
                            { value: 'APPROVED', label: 'Đã duyệt' },
                            { value: 'REJECTED', label: 'Bị trả lại' }
                        ]}
                    ],
                    sortable: true,
                    sortOptions: [
                        { value: 'openedAt', label: 'Mở ca' },
                        { value: 'closedAt', label: 'Đóng ca' }
                    ],
                    fetcher: function (qs) { return hub.api('/shift/history/search?' + qs.toString()); },
                    emptyTitle: 'Chưa có ca làm việc',
                    emptyMessage: 'Mở ca đầu tiên để bắt đầu vận hành.',
                    columns: [
                        { label: 'Mã ca', key: 'id', width: '70px' },
                        { label: 'Chi nhánh', key: 'branchName' },
                        { label: 'Nhân viên', key: 'staffName' },
                        { label: 'Mở lúc', render: function (s) { return s.openedAt ? hub.fmtDateTime(s.openedAt) : '—'; } },
                        { label: 'Đóng lúc', render: function (s) { return s.closedAt ? hub.fmtDateTime(s.closedAt) : '—'; } },
                        { label: 'Tiền thực tế', render: function (s) { return hub.fmtMoney(s.closingCashActual); } },
                        { label: 'Trạng thái', render: function (s) { return statusBadge(s.status); } }
                    ],
                    actions: function (row) {
                        var arr = [];
                        if (row.status === 'OPEN') {
                            arr.push({ label: 'Đóng ca', class: 'primary', href: ctx + '/console?module=shift&action=edit&id=' + row.id });
                        } else if (row.status === 'CLOSED') {
                            arr.push({ label: 'Duyệt', class: 'primary', href: ctx + '/console?module=shift&action=approve&id=' + row.id });
                        }
                        return arr;
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            form.renderFormPage({
                breadcrumb: ['Vận hành', { label: 'Ca làm việc', href: ctx + '/console?module=shift' }, 'Mở ca mới'],
                title: 'Mở ca làm việc',
                subtitle: 'Ghi nhận tiền mặt đầu ca và bắt đầu tính tiền bán vé.',
                submitLabel: 'Mở ca',
                sections: [{
                    title: 'Tiền đầu ca',
                    fields: [
                        { name: 'openingCash', label: 'Tiền mặt đầu ca (VND)', type: 'number', required: true, min: 0, step: 1000,
                          hint: 'Số tiền trong két đầu ca. Thường được Manager xác nhận.' }
                    ]
                }],
                onSubmit: function (data) {
                    hub.api('/shift/open', { method: 'POST', body: data })
                        .then(function () {
                            hub.notify('Đã mở ca thành công.', 'ok');
                            router.go('shift');
                        })
                        .catch(notifyError);
                },
                onCancel: function () { router.go('shift'); }
            });
        },
        edit: function (id) {
            hub.api('/shift/' + id).then(function (shift) {
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Ca làm việc', href: ctx + '/console?module=shift' }, 'Đóng ca'],
                    title: 'Đóng ca làm việc',
                    subtitle: 'Ca #' + shift.id + ' · ' + (shift.branchName || ''),
                    submitLabel: 'Đóng ca',
                    sections: [{
                        title: 'Đối soát tiền mặt',
                        description: 'Đếm tiền thực tế trong két và nhập vào. Nếu lệch với hệ thống, ghi rõ lý do.',
                        fields: [
                            { name: 'shiftId', label: 'Mã ca', type: 'hidden', required: true, value: shift.id },
                            { name: 'closingCashActual', label: 'Tiền mặt thực tế (VND)', type: 'number', required: true, min: 0, step: 1000,
                              hint: 'Đếm thủ công trong két và nhập vào đây.' },
                            { name: 'discrepancyNote', label: 'Ghi chú chênh lệch (nếu có)', type: 'textarea', required: false, rows: 3,
                              hint: 'Bắt buộc nếu có chênh lệch với hệ thống. Manager sẽ đọc ghi chú này khi duyệt.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/shift/close', { method: 'POST', body: data })
                            .then(function () {
                                hub.notify('Đã đóng ca. Chờ Manager duyệt.', 'ok');
                                router.go('shift');
                            })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('shift'); }
                });
            }).catch(notifyError);
        },
        approve: function (id) {
            hub.api('/shift/' + id).then(function (shift) {
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Ca làm việc', href: ctx + '/console?module=shift' }, 'Duyệt ca'],
                    title: 'Duyệt ca làm việc',
                    subtitle: 'Ca #' + shift.id + ' · ' + (shift.branchName || ''),
                    submitLabel: 'Duyệt ca',
                    sections: [{
                        title: 'Xác nhận đối soát',
                        description: 'Kiểm tra tiền thực tế so với hệ thống trước khi duyệt.',
                        fields: [
                            { name: 'shiftId', label: 'Mã ca', type: 'hidden', required: true, value: shift.id },
                            { name: 'note', label: 'Ghi chú xử lý', type: 'textarea', required: false, rows: 3,
                              hint: 'Tùy chọn: ghi chú khi duyệt. Bắt buộc khi trả lại ca.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/shift/approve', { method: 'POST', body: data })
                            .then(function () {
                                hub.notify('Đã duyệt ca.', 'ok');
                                router.go('shift');
                            })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('shift'); }
                });
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  PRODUCT (F&B Catalog Admin)
    // ================================================================
    function renderProductForm(product) {
        var isEdit = !!product;
        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Sản phẩm F&B', href: ctx + '/console?module=product' }, isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa sản phẩm' : 'Tạo sản phẩm mới',
            subtitle: isEdit ? product.name : 'Thêm sản phẩm hoặc combo bắp nước.',
            submitLabel: isEdit ? 'Lưu thay đổi' : 'Tạo sản phẩm',
            sections: [{
                title: 'Thông tin sản phẩm',
                fields: [
                    { name: 'name', label: 'Tên sản phẩm', required: true, value: product && product.name, fullWidth: true },
                    { name: 'description', label: 'Mô tả', type: 'textarea', required: false, value: product && product.description, rows: 3, fullWidth: true },
                    { name: 'price', label: 'Giá bán (VND)', type: 'number', required: true, min: 0, step: 1000, value: product && product.price },
                    { name: 'unit', label: 'Đơn vị', value: (product && product.unit) || 'phần', placeholder: 'phần, ly, hộp…' },
                    { name: 'type', label: 'Loại', type: 'select', required: true, value: (product && product.type) || 'POPCORN', options: [
                        { value: 'POPCORN', label: 'Bắp rang' },
                        { value: 'DRINK', label: 'Nước uống' },
                        { value: 'COMBO', label: 'Combo' },
                        { value: 'OTHER', label: 'Khác' }
                    ]}
                ]
            }],
            onSubmit: function (data) {
                var url = isEdit ? '/concession/products/' + product.id : '/concession/products';
                var method = isEdit ? 'PUT' : 'POST';
                hub.api(url, { method: method, body: data })
                    .then(function () { router.go('product'); })
                    .catch(notifyError);
            },
            onCancel: function () { router.go('product'); }
        });
    }

    router.register('product', {
        list: function () {
            list.render({
                title: 'Sản phẩm F&B',
                subtitle: 'Danh mục bắp nước và combo đang được quản lý.',
                addUrl: ctx + '/console?module=product&action=create',
                addLabel: 'Tạo sản phẩm',
                searchPlaceholder: 'Tìm theo tên sản phẩm…',
                pageSize: 20,
                filters: [{
                    name: 'type', label: 'Loại',
                    options: [
                        { value: 'POPCORN', label: 'Bắp rang' },
                        { value: 'DRINK', label: 'Nước uống' },
                        { value: 'COMBO', label: 'Combo' },
                        { value: 'OTHER', label: 'Khác' }
                    ]
                }],
                sortable: true,
                sortOptions: [
                    { value: 'name', label: 'Tên A→Z' },
                    { value: 'price', label: 'Giá' }
                ],
                fetcher: function () {
                    return hub.api('/concession/products').then(function (list) {
                        return { items: list || [], total: (list || []).length };
                    });
                },
                emptyTitle: 'Chưa có sản phẩm',
                emptyMessage: 'Danh mục bắp nước đang trống.',
                columns: [
                    { label: 'ID', key: 'id', width: '60px' },
                    { label: 'Tên sản phẩm', key: 'name' },
                    { label: 'Loại', key: 'type' },
                    { label: 'Đơn vị', key: 'unit' },
                    { label: 'Giá bán', render: function (r) { return hub.fmtMoney(r.price); } },
                    { label: 'Trạng thái', render: function (r) { return statusBadge(r.status); } }
                ],
                actions: function (row) {
                    var arr = [{ label: 'Sửa', class: 'secondary', href: ctx + '/console?module=product&action=edit&id=' + row.id }];
                    if (row.status === 'ACTIVE') {
                        arr.push({ label: 'Ngưng', class: 'danger', onClick: function () {
                            confirmAction('Ngưng kinh doanh sản phẩm này?', function () {
                                hub.api('/concession/products/' + row.id, { method: 'PUT', body: { action: 'deactivate' } })
                                    .then(function () {
                                        hub.notify('Đã ngưng sản phẩm.', 'ok');
                                        router.go('product');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        create: function () { renderProductForm(null); },
        edit: function (id) {
            hub.api('/concession/products').then(function (list) {
                var product = (list || []).find(function (p) { return String(p.id) === String(id); });
                if (!product) throw new Error('Không tìm thấy sản phẩm');
                renderProductForm(product);
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  BOOKING / TICKET (Customer history + Admin/Manager overview)
    // ================================================================
    router.register('booking', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta === 'ADMIN' || roleMeta === 'BRANCH_MANAGER') {
                list.render({
                    title: 'Đặt vé & vé bán',
                    subtitle: 'Danh sách vé đã bán trong hệ thống (Admin/Manager).',
                    pageSize: 50,
                    filters: [],
                    sortable: true,
                    sortOptions: [
                        { value: 'createdAt', label: 'Mới nhất' }
                    ],
                    fetcher: function () {
                        return hub.api('/booking/list').then(function (data) {
                            var items = (data && data.items) || [];
                            return { items: items, total: items.length };
                        });
                    },
                    emptyTitle: 'Chưa có vé nào',
                    emptyMessage: 'Hệ thống chưa có vé bán nào.',
                    columns: [
                        { label: 'Mã vé', key: 'ticketCode' },
                        { label: 'Khách hàng', key: 'customerName' },
                        { label: 'Phim', key: 'movie' },
                        { label: 'Giờ chiếu', render: function (t) { return t.showtimeStart ? hub.fmtDateTime(t.showtimeStart) : '—'; } },
                        { label: 'Tổng tiền', render: function (t) { return hub.fmtMoney(t.totalAmount); } },
                        { label: 'Trạng thái', render: function (t) { return statusBadge(t.status); } }
                    ]
                });
                return;
            }
            // Customer view = own tickets (with cancel button)
            var viewRoot = document.getElementById('viewRoot');
            viewRoot.replaceChildren(hub.el('div', { class: 'ws-page' }, [
                hub.el('div', { class: 'ws-page-header' }, [
                    hub.el('h1', { class: 'ws-page-title' }, ['Vé của tôi']),
                    hub.el('p', { class: 'ws-page-subtitle' }, ['Lịch sử vé đã đặt.'])
                ])
            ]));
            hub.api('/booking/mine').then(function (list) {
                if (!list || !list.length) {
                    viewRoot.querySelector('.ws-page').appendChild(hub.el('div', { class: 'ws-empty' }, [
                        hub.el('h3', {}, ['Chưa có vé']),
                        hub.el('p', {}, ['Đặt vé từ mục Lịch chiếu.']),
                        hub.el('a', { href: ctx + '/console?module=discovery', class: 'ws-btn primary' }, ['Xem lịch chiếu'])
                    ]));
                    return;
                }
                var table = hub.el('table', { class: 'ws-table' });
                table.innerHTML = '<thead><tr><th>Mã vé</th><th>Suất chiếu</th><th>Ghế</th><th>Tổng tiền</th><th>Trạng thái</th><th></th></tr></thead>';
                var tbody = hub.el('tbody');
                list.forEach(function (t) {
                    var tr = hub.el('tr');
                    var showtime = [t.movieTitle, t.branchName, t.screenName, t.startTime ? hub.fmtDateTime(t.startTime) : null].filter(Boolean).join(' · ');
                    var seats = (t.seats || []).map(function (s) { return s.seatCode || (s.rowLabel + s.colNo); }).join(', ');
                    tr.appendChild(hub.el('td', {}, [t.ticketCode || ('#' + t.id)]));
                    tr.appendChild(hub.el('td', {}, [showtime || '—']));
                    tr.appendChild(hub.el('td', {}, [seats || '—']));
                    tr.appendChild(hub.el('td', {}, [hub.fmtMoney(t.totalAmount)]));
                    tr.appendChild(hub.el('td', {}, [statusBadge(t.status)]));
                    var actions = hub.el('td', { class: 'row-actions' });
                    if (t.status === 'CONFIRMED' && !t.showtimePassed) {
                        var cancelBtn = hub.el('button', { class: 'ws-btn ws-btn-sm danger', type: 'button' }, ['Hủy vé']);
                        cancelBtn.addEventListener('click', function () {
                            confirmAction('Hủy vé ' + t.ticketCode + '?', function () {
                                hub.api('/booking/cancel', { method: 'POST', body: { ticketId: t.id } })
                                    .then(function () {
                                        hub.notify('Đã hủy vé.', 'ok');
                                        router.go('booking');
                                    })
                                    .catch(notifyError);
                            });
                        });
                        actions.appendChild(cancelBtn);
                    } else if (t.status === 'CONFIRMED' && t.showtimePassed) {
                        actions.appendChild(hub.el('span', { class: 'sub muted' }, ['Đã qua giờ']));
                    } else {
                        actions.appendChild(hub.el('span', { class: 'sub muted' }, ['—']));
                    }
                    tr.appendChild(actions);
                    tbody.appendChild(tr);
                });
                table.appendChild(tbody);
                var wrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
                wrap.appendChild(table);
                viewRoot.querySelector('.ws-page').appendChild(wrap);
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  CONCESSION-ORDERS (F&B Staff orders)
    // ================================================================
    router.register('concession-orders', {
        list: function () {
            hub.api('/branch').then(function (branches) {
                list.render({
                    title: 'Đơn hàng Bắp & Nước',
                    subtitle: 'Danh sách đơn F&B tại chi nhánh.',
                    searchPlaceholder: 'Tìm mã đơn / tên / SĐT…',
                    pageSize: 20,
                    filters: [
                        { name: 'branchId', label: 'Chi nhánh',
                          options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
                        { name: 'status', label: 'Trạng thái', options: [
                            { value: 'PENDING', label: 'Chờ thanh toán' },
                            { value: 'READY_FOR_PICKUP', label: 'Sẵn sàng nhận' },
                            { value: 'FULFILLED', label: 'Đã giao' },
                            { value: 'CANCELLED', label: 'Đã hủy' },
                            { value: 'EXPIRED_NO_SHOW', label: 'Quá hạn' }
                        ]}
                    ],
                    toolbarRight: [hub.el('a', {
                        href: ctx + '/console?module=concession-pickup',
                        class: 'ws-btn secondary'
                    }, ['📷 Quét mã nhận'])],
                    fetcher: function (qs) { return hub.api('/concession/orders?' + qs.toString()); },
                    emptyTitle: 'Chưa có đơn hàng',
                    emptyMessage: 'Chi nhánh chưa có đơn F&B nào.',
                    columns: [
                        { label: 'Mã đơn', key: 'orderCode' },
                        { label: 'Chi nhánh', key: 'branchName' },
                        { label: 'Khách hàng', key: 'customerName' },
                        { label: 'Tổng tiền', render: function (o) { return hub.fmtMoney(o.totalAmount); } },
                        { label: 'Mã nhận', key: 'pickupCode' },
                        { label: 'Trạng thái', render: function (o) { return statusBadge(o.status); } },
                        { label: 'Ngày tạo', render: function (o) { return o.createdAt ? hub.fmtDateTime(o.createdAt) : '—'; } }
                    ],
                    actions: function (row) {
                        return [{ label: 'Chi tiết', class: 'secondary', href: ctx + '/console?module=concession-orders&action=view&id=' + row.id }];
                    }
                });
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  REPORT (Admin + Manager)
    // ================================================================
    router.register('report', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var params = new URLSearchParams(window.location.search);
            var today = new Date().toISOString().slice(0, 10);
            var monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);

            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Báo cáo doanh thu & vận hành']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Dữ liệu giới hạn theo phạm vi chi nhánh của bạn.'])
            ]));

            var toolbar = hub.el('div', { class: 'ws-list-toolbar' });
            var left = hub.el('div', { class: 'ws-list-toolbar-left' });
            var fromInput = hub.el('input', { type: 'date', value: params.get('from') || monthAgo, 'aria-label': 'Từ ngày', style: 'padding:8px 12px;border:1px solid var(--ws-line);border-radius:8px;background:#fff' });
            var toInput = hub.el('input', { type: 'date', value: params.get('to') || today, 'aria-label': 'Đến ngày', style: 'padding:8px 12px;border:1px solid var(--ws-line);border-radius:8px;background:#fff' });
            left.appendChild(hub.el('label', { style: 'display:flex;align-items:center;gap:6px;font-size:0.86rem;color:var(--ws-text-muted)' }, ['Từ:', fromInput]));
            left.appendChild(hub.el('label', { style: 'display:flex;align-items:center;gap:6px;font-size:0.86rem;color:var(--ws-text-muted)' }, ['Đến:', toInput]));
            toolbar.appendChild(left);
            var right = hub.el('div', { class: 'ws-list-toolbar-right' });
            var runBtn = hub.el('button', { class: 'ws-btn primary', type: 'button' }, ['Xem báo cáo']);
            right.appendChild(runBtn);
            toolbar.appendChild(right);
            page.appendChild(toolbar);

            var kpis = hub.el('div', { class: 'kpi-grid', style: 'margin-bottom:20px' });
            var tableWrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
            page.appendChild(kpis);
            page.appendChild(tableWrap);
            viewRoot.replaceChildren(page);

            function load() {
                kpis.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                var qs = new URLSearchParams();
                if (fromInput.value) qs.set('from', fromInput.value);
                if (toInput.value) qs.set('to', toInput.value);
                hub.api('/report/summary?' + qs.toString()).then(function (data) {
                    var rev = (data && data.revenue) || {};
                    var ops = (data && data.operations) || [];
                    kpis.replaceChildren(
                        hub.el('div', { class: 'kpi' }, [hub.el('span', {}, ['Doanh thu']), hub.el('strong', {}, [hub.fmtMoney(rev.revenue)])]),
                        hub.el('div', { class: 'kpi' }, [hub.el('span', {}, ['Vé đã bán']), hub.el('strong', {}, [String(rev.ticketsSold || 0)])]),
                        hub.el('div', { class: 'kpi' }, [hub.el('span', {}, ['Vé đã hủy']), hub.el('strong', {}, [String(rev.ticketsCancelled || 0)])]),
                        hub.el('div', { class: 'kpi' }, [hub.el('span', {}, ['Hoàn tiền']), hub.el('strong', {}, [hub.fmtMoney(rev.refundTotal)])])
                    );
                    if (!ops.length) {
                        tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Chưa có dữ liệu vận hành']),
                            hub.el('p', {}, ['Báo cáo sẽ xuất hiện khi có ca đã đóng trong khoảng ngày đã chọn.'])
                        ]));
                        return;
                    }
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Chi nhánh</th><th>Số ca</th><th>Chênh lệch</th><th>Chờ duyệt</th></tr></thead>';
                    var tbody = hub.el('tbody');
                    ops.forEach(function (r) {
                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', {}, [r.branchName || '—']),
                            hub.el('td', {}, [String(r.shiftCount)]),
                            hub.el('td', {}, [hub.fmtMoney(r.totalDiscrepancy)]),
                            hub.el('td', {}, [String(r.pendingApprovalCount)])
                        ]));
                    });
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);
                }).catch(function (e) {
                    notifyError(e);
                    kpis.replaceChildren();
                    tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [hub.el('h3', {}, ['Lỗi']), hub.el('p', {}, [e.message])]));
                });
            }
            runBtn.addEventListener('click', load);
            load();
        }
    });

    // ================================================================
    //  NOTIFICATION
    // ================================================================
    router.register('notification', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Thông báo']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Thông báo in-app của bạn.'])
            ]));
            var toolbar = hub.el('div', { class: 'ws-list-toolbar' });
            var left = hub.el('div', { class: 'ws-list-toolbar-left' });
            var filterSel = hub.el('select', { class: 'ws-select', 'aria-label': 'Lọc' }, [
                hub.el('option', { value: '' }, ['Tất cả']),
                hub.el('option', { value: 'unread' }, ['Chưa đọc'])
            ]);
            left.appendChild(filterSel);
            toolbar.appendChild(left);
            var right = hub.el('div', { class: 'ws-list-toolbar-right' });
            var markAllBtn = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Đánh dấu tất cả đã đọc']);
            right.appendChild(markAllBtn);
            toolbar.appendChild(right);
            page.appendChild(toolbar);

            var tableWrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
            page.appendChild(tableWrap);
            viewRoot.replaceChildren(page);

            function load() {
                tableWrap.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                var qs = filterSel.value === 'unread' ? '?unreadOnly=true' : '';
                hub.api('/notification' + qs).then(function (data) {
                    var list = (data && data.notifications) || [];
                    if (!list.length) {
                        tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Không có thông báo']),
                            hub.el('p', {}, ['Bạn sẽ nhận thông báo khi có hoạt động mới.'])
                        ]));
                        return;
                    }
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Nội dung</th><th>Thời điểm</th><th>Trạng thái</th><th></th></tr></thead>';
                    var tbody = hub.el('tbody');
                    list.forEach(function (n) {
                        var tr = hub.el('tr');
                        tr.appendChild(hub.el('td', {}, [n.body || n.title || '—']));
                        tr.appendChild(hub.el('td', {}, [n.createdAt ? hub.fmtDateTime(n.createdAt) : '—']));
                        tr.appendChild(hub.el('td', {}, [statusBadge(n.read ? 'USED' : 'PENDING')]));
                        var actions = hub.el('td', { class: 'row-actions' });
                        if (!n.read) {
                            var btn = hub.el('button', { class: 'ws-btn ws-btn-sm secondary', type: 'button' }, ['Đã đọc']);
                            btn.addEventListener('click', function () {
                                hub.api('/notification/' + n.id + '/read', { method: 'POST' })
                                    .then(load)
                                    .catch(notifyError);
                            });
                            actions.appendChild(btn);
                        }
                        tr.appendChild(actions);
                        tbody.appendChild(tr);
                    });
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);
                }).catch(notifyError);
            }
            markAllBtn.addEventListener('click', function () {
                hub.api('/notification/read-all', { method: 'POST' })
                    .then(function () {
                        hub.notify('Đã đánh dấu tất cả là đã đọc.', 'ok');
                        load();
                    })
                    .catch(notifyError);
            });
            filterSel.addEventListener('change', load);
            load();
        }
    });

    // ================================================================
    //  WALLET (Customer + Admin/Manager)
    // ================================================================
    router.register('wallet', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta === 'CUSTOMER') {
                var viewRoot = document.getElementById('viewRoot');
                viewRoot.replaceChildren(hub.el('div', { class: 'ws-page' }, [
                    hub.el('div', { class: 'ws-page-header' }, [
                        hub.el('h1', { class: 'ws-page-title' }, ['Ví của tôi']),
                        hub.el('p', { class: 'ws-page-subtitle' }, ['Số dư và lịch sử giao dịch.'])
                    ])
                ]));
                hub.api('/wallet').then(function (data) {
                    var page = viewRoot.querySelector('.ws-page');
                    var w = data && data.wallet;
                    var kpi = hub.el('div', { class: 'kpi-grid' });
                    kpi.appendChild(hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Số dư hiện tại']),
                        hub.el('strong', {}, [hub.fmtMoney((w && w.balance) || 0)])
                    ]));
                    page.appendChild(kpi);
                    var list = (data && data.transactions) || [];
                    if (!list.length) {
                        page.appendChild(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Chưa có giao dịch']),
                            hub.el('p', {}, ['Nạp tiền để bắt đầu sử dụng ví.'])
                        ]));
                        return;
                    }
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Loại</th><th>Số tiền</th><th>Số dư sau</th><th>Thời gian</th><th>Mô tả</th></tr></thead>';
                    var tbody = hub.el('tbody');
                    list.forEach(function (tx) {
                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', {}, [tx.type || '—']),
                            hub.el('td', {}, [hub.fmtMoney(tx.amount)]),
                            hub.el('td', {}, [hub.fmtMoney(tx.balanceAfter)]),
                            hub.el('td', {}, [tx.createdAt ? hub.fmtDateTime(tx.createdAt) : '—']),
                            hub.el('td', {}, [tx.description || '—'])
                        ]));
                    });
                    table.appendChild(tbody);
                    var wrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden;margin-top:16px' });
                    wrap.appendChild(table);
                    page.appendChild(wrap);
                }).catch(notifyError);
                return;
            }
            // Admin/Manager view
            list.render({
                title: 'Sao kê ví',
                subtitle: 'Tổng hợp giao dịch ví của tất cả khách hàng.',
                pageSize: 50,
                fetcher: function () {
                    return hub.api('/admin/wallet/transactions?limit=200').then(function (data) {
                        return { items: (data && data.items) || [], total: (data && data.total) || 0 };
                    });
                },
                emptyTitle: 'Chưa có giao dịch',
                columns: [
                    { label: 'ID', key: 'id', width: '70px' },
                    { label: 'User', key: 'userId' },
                    { label: 'Loại', key: 'type' },
                    { label: 'Số tiền', render: function (t) { return hub.fmtMoney(t.amount); } },
                    { label: 'Số dư sau', render: function (t) { return hub.fmtMoney(t.balanceAfter); } },
                    { label: 'Mô tả', key: 'description' },
                    { label: 'Thời gian', render: function (t) { return t.createdAt ? hub.fmtDateTime(t.createdAt) : '—'; } }
                ]
            });
        }
    });

    // ================================================================
    //  CUSTOMER-POINTS (Staff adjust loyalty)
    // ================================================================
    router.register('customer-points', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Điều chỉnh điểm khách hàng']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Tra cứu lịch sử điểm và điều chỉnh cho khách.'])
            ]));
            var toolbar = hub.el('div', { class: 'ws-list-toolbar' });
            var left = hub.el('div', { class: 'ws-list-toolbar-left' });
            var customerInput = hub.el('input', {
                type: 'number', placeholder: 'Nhập ID khách hàng…',
                'aria-label': 'ID khách hàng',
                style: 'padding:8px 12px;border:1px solid var(--ws-line);border-radius:8px;background:#fff;min-width:180px'
            });
            var lookupBtn = hub.el('button', { class: 'ws-btn primary', type: 'button' }, ['Tra cứu']);
            left.appendChild(customerInput);
            left.appendChild(lookupBtn);
            toolbar.appendChild(left);
            var right = hub.el('div', { class: 'ws-list-toolbar-right' });
            var adjustBtn = hub.el('button', { class: 'ws-btn primary', type: 'button' }, ['+ Điều chỉnh điểm']);
            adjustBtn.disabled = true;
            right.appendChild(adjustBtn);
            toolbar.appendChild(right);
            page.appendChild(toolbar);
            var tableWrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
            page.appendChild(tableWrap);
            viewRoot.replaceChildren(page);

            function loadLedger(customerId) {
                if (!customerId) {
                    tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                        hub.el('h3', {}, ['Nhập ID khách hàng để xem lịch sử']),
                        hub.el('p', {}, ['Nhân viên có quyền sẽ thấy số dư và lịch sử điều chỉnh.'])
                    ]));
                    adjustBtn.disabled = true;
                    return;
                }
                tableWrap.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                hub.api('/loyalty/ledger?customerId=' + customerId).then(function (data) {
                    var items = (data && data.items) || [];
                    if (!items.length) {
                        tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Chưa có giao dịch điểm']),
                            hub.el('p', {}, ['Khách hàng chưa phát sinh điểm thưởng.'])
                        ]));
                        adjustBtn.disabled = false;
                        return;
                    }
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Ngày</th><th>Loại</th><th>Điểm</th><th>Số dư sau</th><th>Lý do</th><th>Nhân viên</th></tr></thead>';
                    var tbody = hub.el('tbody');
                    items.forEach(function (e) {
                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', {}, [e.createdAt ? hub.fmtDateTime(e.createdAt) : '—']),
                            hub.el('td', {}, [e.refType || '—']),
                            hub.el('td', {}, [(e.delta > 0 ? '+' : '') + e.delta]),
                            hub.el('td', {}, [String(e.balanceAfter || 0)]),
                            hub.el('td', {}, [e.reason || '—']),
                            hub.el('td', {}, [e.staffId ? '#' + e.staffId : '—'])
                        ]));
                    });
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);
                    adjustBtn.disabled = false;
                }).catch(notifyError);
            }
            lookupBtn.addEventListener('click', function () { loadLedger(customerInput.value); });
            customerInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') loadLedger(customerInput.value); });
            adjustBtn.addEventListener('click', function () {
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Điểm khách hàng', href: ctx + '/console?module=customer-points' }, 'Điều chỉnh'],
                    title: 'Điều chỉnh điểm khách hàng',
                    subtitle: 'Mọi điều chỉnh sẽ được ghi log audit.',
                    submitLabel: 'Điều chỉnh',
                    sections: [{
                        title: 'Thông tin điều chỉnh',
                        fields: [
                            { name: 'customerId', label: 'ID khách hàng', type: 'number', required: true, value: customerInput.value, min: 1 },
                            { name: 'delta', label: 'Điểm (+ hoặc -)', type: 'number', required: true, step: 1, min: -10000, max: 10000,
                              hint: 'Tối đa 10.000 điểm mỗi lần. Số âm sẽ trừ điểm.' },
                            { name: 'reason', label: 'Lý do', type: 'textarea', required: true, rows: 3, minLength: 5,
                              hint: 'Tối thiểu 5 ký tự. Sẽ hiển thị trong lịch sử điểm của khách.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/loyalty/adjust', { method: 'POST', body: data })
                            .then(function () {
                                hub.notify('Đã điều chỉnh điểm.', 'ok');
                                router.go('customer-points');
                            })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('customer-points'); }
                });
            });
            loadLedger(null);
        }
    });

    // ================================================================
    //  CONCESSION-PICKUP (QR scan)
    // ================================================================
    router.register('concession-pickup', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Quét mã F&B']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Nhập mã nhận hàng để tra cứu đơn F&B.'])
            ]));
            var card = hub.el('div', { class: 'ws-card', style: 'padding:24px;max-width:540px' });
            card.appendChild(hub.el('label', { style: 'display:block;margin-bottom:6px;font-weight:600' }, ['Mã nhận hàng']));
            var codeInput = hub.el('input', {
                type: 'text', placeholder: 'PCK-XXXXX',
                style: 'width:100%;padding:12px;border:1px solid var(--ws-line);border-radius:8px;font-size:1rem;font-family:monospace;text-transform:uppercase'
            });
            card.appendChild(codeInput);
            var scanBtn = hub.el('button', { class: 'ws-btn primary', type: 'button', style: 'margin-top:12px;width:100%' }, ['Tra cứu']);
            card.appendChild(scanBtn);
            var result = hub.el('div', { style: 'margin-top:20px' });
            card.appendChild(result);
            page.appendChild(card);
            viewRoot.replaceChildren(page);

            var lookupFn = function () {
                var code = codeInput.value.trim();
                if (!code) return;
                result.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                hub.api('/concession/scan', { method: 'POST', body: { code: code.toUpperCase() } }).then(function (data) {
                    if (!data.canRedeem) {
                        result.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Không thể giao đơn này']),
                            hub.el('p', {}, [data.message || 'Đơn không ở trạng thái sẵn sàng nhận.'])
                        ]));
                        return;
                    }
                    var box = hub.el('div', { class: 'ws-card', style: 'padding:20px' });
                    box.appendChild(hub.el('h3', {}, ['✓ Sẵn sàng giao hàng']));
                    var grid = hub.el('div', { style: 'display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px' });
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Mã đơn:']), ' ', data.orderCode || '—']));
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Khách:']), ' ', data.customerName || '—']));
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Tổng tiền:']), ' ', hub.fmtMoney(data.totalAmount)]));
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Chi nhánh:']), ' ', data.branchId || '—']));
                    box.appendChild(grid);
                    var lines = (data.lines || []).map(function (l) {
                        return hub.el('div', { style: 'padding:6px 0;border-bottom:1px solid var(--ws-line)' },
                            [l.productName + ' ×' + l.qty + '  ', hub.el('strong', {}, [hub.fmtMoney(l.subtotal)])]);
                    });
                    if (lines.length) {
                        box.appendChild(hub.el('h4', { style: 'margin-top:16px' }, ['Sản phẩm']));
                        lines.forEach(function (l) { box.appendChild(l); });
                    }
                    var confirmBtn = hub.el('button', { class: 'ws-btn primary', type: 'button', style: 'margin-top:16px;width:100%' }, ['✓ Xác nhận giao hàng']);
                    confirmBtn.addEventListener('click', function () {
                        hub.api('/concession/redeem', { method: 'POST', body: { orderId: data.orderId } })
                            .then(function () {
                                hub.notify('Đã giao đơn hàng.', 'ok');
                                result.replaceChildren();
                                codeInput.value = '';
                            })
                            .catch(notifyError);
                    });
                    box.appendChild(confirmBtn);
                    result.replaceChildren(box);
                }).catch(function (e) {
                    result.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                        hub.el('h3', {}, ['Không tìm thấy đơn']),
                        hub.el('p', {}, [e.message || 'Vui lòng kiểm tra lại mã.'])
                    ]));
                });
            };
            scanBtn.addEventListener('click', lookupFn);
            codeInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') lookupFn(); });
            codeInput.focus();
        }
    });

    // ================================================================
    //  PROFILE (Customer account)
    // ================================================================
    router.register('profile', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            var header = hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Hồ sơ cá nhân']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Quản lý thông tin tài khoản, hạng thành viên và số dư ví.'])
            ]);
            var content = hub.el('div', { class: 'ws-card' });
            content.appendChild(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
            page.appendChild(header);
            page.appendChild(content);
            viewRoot.replaceChildren(page);

            hub.api('/api/profile').then(function (data) {
                var user = data && data.user ? data.user : {};
                var membership = data && data.profile ? data.profile : {};
                var wallet = data && data.wallet ? data.wallet : {};
                var form = hub.el('form', { class: 'ws-form' });
                var fields = hub.el('div', { class: 'ws-form-grid' });
                var fullName = hub.el('input', {
                    type: 'text', name: 'fullName', required: 'required',
                    value: user.fullName || '', maxlength: '120'
                });
                var phone = hub.el('input', {
                    type: 'tel', name: 'phone', value: user.phone || '', maxlength: '30'
                });
                fields.appendChild(hub.el('label', {}, ['Họ và tên', fullName]));
                fields.appendChild(hub.el('label', {}, ['Số điện thoại', phone]));
                fields.appendChild(hub.el('label', {}, ['Email', hub.el('input', {
                    type: 'email', value: user.email || '', disabled: 'disabled'
                })]));
                fields.appendChild(hub.el('label', {}, ['Vai trò', hub.el('input', {
                    type: 'text', value: user.role || 'CUSTOMER', disabled: 'disabled'
                })]));
                form.appendChild(fields);
                form.appendChild(hub.el('div', { class: 'ws-form-actions' }, [
                    hub.el('button', { class: 'ws-btn primary', type: 'submit' }, ['Lưu thay đổi'])
                ]));

                var summary = hub.el('div', { class: 'kpi-grid', style: 'margin-top:20px' }, [
                    hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Hạng thành viên']),
                        hub.el('strong', {}, [String(membership.tier || 'STANDARD')])
                    ]),
                    hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Điểm tích lũy']),
                        hub.el('strong', {}, [String(membership.points || 0)])
                    ]),
                    hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Số dư ví']),
                        hub.el('strong', {}, [hub.fmtMoney(wallet.balance || 0)])
                    ])
                ]);
                form.addEventListener('submit', function (event) {
                    event.preventDefault();
                    var submit = form.querySelector('button[type="submit"]');
                    submit.disabled = true;
                    hub.api('/api/profile', {
                        method: 'POST',
                        body: { fullName: fullName.value.trim(), phone: phone.value.trim() }
                    }).then(function (updated) {
                        var updatedUser = updated && updated.user ? updated.user : {};
                        fullName.value = updatedUser.fullName || fullName.value;
                        phone.value = updatedUser.phone || '';
                        hub.notify('Đã cập nhật hồ sơ.', 'ok');
                    }).catch(notifyError).then(function () {
                        submit.disabled = false;
                    });
                });
                content.replaceChildren(form, summary);
            }).catch(function (error) {
                content.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không tải được hồ sơ']),
                    hub.el('p', {}, [error.message || 'Vui lòng thử lại.'])
                ]));
            });
        }
    });
})();
