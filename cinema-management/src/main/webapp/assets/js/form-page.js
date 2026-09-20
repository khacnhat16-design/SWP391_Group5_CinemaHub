/**
 * form-page.js — Render full-page Create/Edit form thay cho modal.
 *
 * Thay vì mở modal, các form Create/Edit giờ là một trang riêng với:
 * - Breadcrumb (Console > Module > Create/Edit)
 * - Page title + subtitle
 * - Form card với sections rõ ràng
 * - Required indicator (*)
 * - Validation message inline (client-side)
 * - Cancel/Back + Create/Save buttons
 *
 * Sử dụng:
 *   CinemaHubForm.renderFormPage({
 *       breadcrumb: ['Khu vực quản lý', 'Người dùng', 'Tạo mới'],
 *       title: 'Tạo người dùng',
 *       subtitle: 'Tạo tài khoản nhân viên hoặc khách hàng',
 *       sections: [{
 *           title: 'Thông tin cơ bản',
 *           description: 'Các trường bắt buộc',
 *           fields: [
 *               { name: 'email', label: 'Email', type: 'email', required: true,
 *                 placeholder: 'user@example.com' },
 *               ...
 *           ]
 *       }, ...],
 *       onSubmit: async (data) => { await hub.api('/api/users', { method: 'POST', body: data }); },
 *       onCancel: () => history.back(),
 *       submitLabel: 'Tạo người dùng',
 *       cancelLabel: 'Hủy'
 *   });
 */
(function () {
    'use strict';

    const hub = window.CinemaHub;
    if (!hub) return;

    const el = hub.el;
    const text = (v, fb) => (v == null || v === '') ? (fb || '—') : String(v);

    // ─── helpers ────────────────────────────────────────────────────────────

    function buildField(field) {
        const wrap = el('div', { class: 'ws-field' + (field.fullWidth ? ' full' : '') });

        // Label gồm tên field + dấu * nếu required; aria-required để screen reader
        // vẫn báo lỗi khi user để trống.
        const labelChildren = [field.label || ''];
        if (field.required) {
            labelChildren.push(el('span', { class: 'ws-required-marker', 'aria-hidden': 'true' }, [' *']));
        }
        const label = el('label', { for: 'fld-' + field.name }, labelChildren);
        if (field.required) {
            label.setAttribute('aria-required', 'true');
        }
        wrap.appendChild(label);

        if (field.hint) {
            wrap.appendChild(el('small', { class: 'ws-field-hint' }, [field.hint]));
        }

        let control;
        if (field.type === 'hidden') {
            control = el('input', {
                id: 'fld-' + field.name,
                name: field.name,
                type: 'hidden',
                value: field.value ?? '',
                required: field.required || false
            });
            wrap.classList.add('ws-field-hidden');
            wrap.appendChild(control);
            return wrap;
        } else if (field.type === 'select') {
            control = el('select', {
                id: 'fld-' + field.name,
                name: field.name,
                required: field.required || false
            });
            const opts = field.options || [];
            if (!field.required) {
                control.appendChild(el('option', { value: '' }, ['— Chọn —']));
            }
            opts.forEach(o => {
                const value = typeof o === 'string' ? o : o.value;
                const labelText = typeof o === 'string' ? o : o.label;
                control.appendChild(el('option', {
                    value: value,
                    selected: String(value) === String(field.value ?? '')
                }, [labelText]));
            });
        } else if (field.type === 'textarea') {
            control = el('textarea', {
                id: 'fld-' + field.name,
                name: field.name,
                rows: field.rows || 4,
                placeholder: field.placeholder || '',
                required: field.required || false
            });
            control.value = field.value ?? '';
        } else if (field.type === 'file') {
            control = el('input', {
                id: 'fld-' + field.name,
                name: field.name,
                type: 'file',
                accept: field.accept || 'image/*',
                required: field.required || false
            });
        } else if (field.type === 'checkbox') {
            const cbWrap = el('div', { class: 'ws-checkbox' });
            control = el('input', {
                id: 'fld-' + field.name,
                name: field.name,
                type: 'checkbox',
                value: 'true'
            });
            if (field.value) control.checked = true;
            cbWrap.appendChild(control);
            if (field.checkboxLabel) {
                cbWrap.appendChild(el('label', { for: 'fld-' + field.name, class: 'ws-checkbox-label' }, [field.checkboxLabel]));
            }
            wrap.appendChild(cbWrap);
            wrap.appendChild(el('div', { class: 'ws-field-error', 'data-error-for': field.name }));
            return wrap;
        } else {
            control = el('input', {
                id: 'fld-' + field.name,
                name: field.name,
                type: field.type || 'text',
                placeholder: field.placeholder || '',
                value: field.value ?? '',
                required: field.required || false,
                min: field.min,
                max: field.max,
                step: field.step,
                pattern: field.pattern,
                autocomplete: field.autocomplete
            });
        }

        wrap.appendChild(control);
        wrap.appendChild(el('div', { class: 'ws-field-error', 'data-error-for': field.name }));
        return wrap;
    }

    function validate(form, sections) {
        let firstInvalid = null;
        sections.forEach(section => {
            section.fields.forEach(f => {
                const errorBox = form.querySelector(`[data-error-for="${f.name}"]`);
                if (errorBox) errorBox.textContent = '';

                if (f.type === 'checkbox') {
                    return; // optional field, validate by required attr
                }

                const input = form.querySelector(`[name="${f.name}"]`);
                if (!input) return;

                const value = (input.value || '').trim();
                let error = '';

                if (f.required && !value) {
                    error = 'Trường bắt buộc';
                } else if (f.type === 'email' && value && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
                    error = 'Email không hợp lệ';
                } else if (f.type === 'tel' && value && f.pattern && !new RegExp(f.pattern).test(value)) {
                    error = f.patternMessage || 'Định dạng không hợp lệ';
                } else if (f.type === 'number' && value) {
                    const n = Number(value);
                    if (isNaN(n)) error = 'Phải là số';
                    else if (f.min != null && n < f.min) error = `Tối thiểu ${f.min}`;
                    else if (f.max != null && n > f.max) error = `Tối đa ${f.max}`;
                } else if (f.minLength && value.length < f.minLength) {
                    error = `Tối thiểu ${f.minLength} ký tự`;
                } else if (f.maxLength && value.length > f.maxLength) {
                    error = `Tối đa ${f.maxLength} ký tự`;
                } else if (f.customValidate) {
                    const customError = f.customValidate(value);
                    if (customError) error = customError;
                }

                if (error && errorBox) {
                    errorBox.textContent = error;
                    input.classList.add('ws-field-invalid');
                    input.setAttribute('aria-invalid', 'true');
                    if (!firstInvalid) firstInvalid = input;
                } else {
                    input.classList.remove('ws-field-invalid');
                    input.removeAttribute('aria-invalid');
                }
            });
        });
        return firstInvalid;
    }

    function collectData(form) {
        const data = {};
        const fd = new FormData(form);
        for (const [k, v] of fd.entries()) {
            data[k] = v;
        }
        // Checkbox unchecked không xuất hiện trong FormData.entries() → cần loop
        // riêng để set giá trị '' (để backend parse/check nullable) thay vì undefined.
        form.querySelectorAll('input[type="checkbox"]').forEach(cb => {
            data[cb.name] = cb.checked ? (cb.value || 'true') : '';
        });
        return data;
    }

    function renderFormPage(opts) {
        const viewRoot = document.getElementById('viewRoot');
        if (!viewRoot) return;

        const {
            breadcrumb = [],
            title,
            subtitle,
            sections = [],
            onSubmit,
            onCancel,
            submitLabel = 'Lưu',
            cancelLabel = 'Hủy',
            backUrl, // optional: explicit back URL (default history.back)
            renderFooter // optional: hook to inject extra sections into the form (e.g. poster uploader)
        } = opts;

        // Đồng bộ topbar `<h1>` + `<title>` với title của form page — fix trường hợp
        // module không có JSP riêng và topbar JSP chỉ hiển thị default "Bảng điều khiển".
        if (hub && typeof hub.setWorkspaceHeader === 'function' && title) {
            hub.setWorkspaceHeader(title);
        }

        const breadcrumbEl = el('nav', { class: 'ws-breadcrumb', 'aria-label': 'Breadcrumb' });
        breadcrumb.forEach((item, idx) => {
            if (idx > 0) {
                breadcrumbEl.appendChild(el('span', { class: 'ws-breadcrumb-sep', 'aria-hidden': 'true' }, ['/']));
            }
            const isLast = idx === breadcrumb.length - 1;
            if (isLast) {
                // Item cuối không link (current page) — dùng aria-current để a11y rõ ràng.
                breadcrumbEl.appendChild(el('span', { class: 'ws-breadcrumb-current', 'aria-current': 'page' }, [item]));
            } else {
                breadcrumbEl.appendChild(el('a', { href: item.href || '#', class: 'ws-breadcrumb-link' }, [item.label || item]));
            }
        });

        const header = el('div', { class: 'ws-page-header' });
        header.appendChild(el('h1', { class: 'ws-page-title' }, [title || '']));
        if (subtitle) header.appendChild(el('p', { class: 'ws-page-subtitle' }, [subtitle]));

        const card = el('div', { class: 'ws-card ws-page-form' });
        const form = el('form', { class: 'ws-form', novalidate: 'novalidate', autocomplete: 'on' });
        form.setAttribute('aria-labelledby', 'form-title');

        sections.forEach(section => {
            const sectionEl = el('section', { class: 'ws-form-section' });
            if (section.title) {
                sectionEl.appendChild(el('h2', { class: 'ws-form-section-title', id: 'form-title' }, [section.title]));
            }
            if (section.description) {
                sectionEl.appendChild(el('p', { class: 'ws-form-section-desc' }, [section.description]));
            }
            const grid = el('div', {
                class: 'ws-form-grid' + (section.singleColumn ? ' single-column' : '')
            });
            section.fields.forEach(f => grid.appendChild(buildField(f)));
            sectionEl.appendChild(grid);
            form.appendChild(sectionEl);
        });

        // Banner lỗi server (vd. 400/409 từ API): chỉ render khi submit fail.
        const serverError = el('div', {
            class: 'ws-form-server-error',
            role: 'alert',
            hidden: 'hidden'
        });
        form.appendChild(serverError);

        // renderFooter cho phép module tuỳ biến (vd. thêm poster uploader) mà vẫn
        // đảm bảo thứ tự: section declared → footer (có thể thêm hidden input) →
        // action buttons — để submit handler collect đủ field.
        if (typeof renderFooter === 'function') {
            renderFooter(form);
        }

        const actions = el('div', { class: 'ws-form-actions' });
        const cancelBtn = el('button', {
            type: 'button',
            class: 'ws-btn secondary'
        }, [cancelLabel]);
        cancelBtn.addEventListener('click', () => {
            if (typeof onCancel === 'function') onCancel();
            else if (backUrl) window.location.href = backUrl;
            else history.back();
        });
        const submitBtn = el('button', {
            type: 'submit',
            class: 'ws-btn primary'
        }, [submitLabel]);
        actions.appendChild(cancelBtn);
        actions.appendChild(submitBtn);
        form.appendChild(actions);

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            serverError.hidden = true;
            var firstInvalid = validate(form, sections);
            if (firstInvalid) {
                firstInvalid.focus();
                return;
            }
            var data = collectData(form);
            submitBtn.disabled = true;
            cancelBtn.disabled = true;
            Promise.resolve().then(function () { return onSubmit(data); }).then(function () {
                hub.notify('Đã lưu thay đổi.', 'ok');
            }).catch(function (err) {
                serverError.textContent = err.message || 'Lỗi hệ thống. Vui lòng thử lại.';
                serverError.hidden = false;
                submitBtn.disabled = false;
                cancelBtn.disabled = false;
                hub.notify(err.message || 'Không thể lưu dữ liệu.', 'err');
            });
        });

        card.appendChild(form);

        const page = el('div', { class: 'ws-page ws-page-form-host' });
        page.appendChild(breadcrumbEl);
        page.appendChild(header);
        page.appendChild(card);

        viewRoot.replaceChildren(page);

        // Focus vào input đầu tiên để keyboard user có thể gõ ngay, không cần Tab.
        const firstInput = form.querySelector('input, select, textarea');
        if (firstInput) firstInput.focus();
    }

    window.CinemaHubForm = {
        renderFormPage,
        buildField,
        validate,
        collectData
    };
})();
