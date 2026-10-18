// home.js — Public landing page (/) renderer
// Renders the "Phim đang chiếu" grid by calling GET /movie, ordered newest
// (release_date DESC, id DESC) — same ordering as MovieDAO.findAll.
// To make newly-added titles visible immediately, we keep ALL PUBLISHED movies
// (no date filter) and label each card "Đang chiếu" / "Sắp chiếu" / "Ngừng
// chiếu" based on release_date / end_date so the customer is never confused.
(function () {
    'use strict';

    function init() {
        const grid = document.getElementById('nowShowingGrid');
        if (!grid || !window.CinemaHub || !CinemaHub.api) return;
        CinemaHub.api('/movie')
            .then(function (data) {
                const all = Array.isArray(data) ? data : (data && Array.isArray(data.items) ? data.items : []);
                const today = new Date();
                today.setHours(0, 0, 0, 0);
                const list = (all || [])
                    .filter(function (m) {
                        if (!m) return false;
                        if (m.status && m.status !== 'PUBLISHED') return false;
                        return true;
                    })
                    .map(function (m) {
                        m._state = classifyMovie(m, today);
                        return m;
                    })
                    .sort(function (a, b) {
                        // Newest first by release_date, fallback to id DESC.
                        const ad = a.releaseDate ? new Date(a.releaseDate).getTime() : 0;
                        const bd = b.releaseDate ? new Date(b.releaseDate).getTime() : 0;
                        if (bd !== ad) return bd - ad;
                        return (b.id || 0) - (a.id || 0);
                    });

                grid.removeAttribute('aria-busy');
                if (!list.length) {
                    grid.replaceChildren(CinemaHub.el('div', { class: 'empty-state' }, [
                        CinemaHub.el('h3', {}, ['Chưa có phim đang chiếu']),
                        CinemaHub.el('p', {}, ['Hãy quay lại sau khi phim mới được công chiếu.'])
                    ]));
                    return;
                }

                const cards = list.map(renderMovieCard);
                grid.replaceChildren.apply(grid, cards);
            })
            .catch(function (err) {
                grid.removeAttribute('aria-busy');
                grid.replaceChildren(CinemaHub.el('div', { class: 'empty-state' }, [
                    CinemaHub.el('p', {}, ['Không thể tải danh sách phim: ' + (err && err.message ? err.message : 'Lỗi máy chủ.')])
                ]));
            });
    }

    function classifyMovie(m, today) {
        const release = m.releaseDate ? new Date(m.releaseDate) : null;
        const end = m.endDate ? new Date(m.endDate) : null;
        const validRel = release && !isNaN(release.getTime());
        const validEnd = end && !isNaN(end.getTime());
        if (validRel && release > today) return 'UPCOMING';
        if (validEnd && end < today) return 'ENDED';
        return 'NOW_SHOWING';
    }

    function renderMovieCard(m) {
        const hub = window.CinemaHub;
        const title = m.title || ('Phim #' + m.id);
        const poster = m.posterUrl;
        const rating = m.averageRating != null ? Number(m.averageRating).toFixed(1) : null;
        const detailHref = (hub.ctx || '') + '/console?module=discovery&movieId=' + encodeURIComponent(m.id);
        const meta = [];
        if (m.genre) meta.push(m.genre);
        if (m.durationMin) meta.push(m.durationMin + ' phút');
        if (m.rating) meta.push('Phân loại: ' + m.rating);
        if (m.releaseDate) meta.push('Khởi chiếu: ' + formatDate(m.releaseDate));

        const posterNode = poster
            ? hub.el('img', {
                src: poster,
                alt: title,
                loading: 'lazy',
                referrerpolicy: 'no-referrer',
                'data-poster-fallback': 'poster-placeholder'
            })
            : hub.el('div', { class: 'poster-placeholder' }, ['NO POSTER']);

        const stateBadge = renderStateBadge(m._state);
        const quickBookLabel = m._state === 'UPCOMING' ? 'Thông tin' : 'Đặt vé';

        return hub.el('article', { class: 'movie-card movie-card-' + (m._state || '').toLowerCase() }, [
            hub.el('a', { class: 'poster', href: detailHref }, [
                posterNode,
                stateBadge,
                rating ? hub.el('span', { class: 'rating' }, ['★ ' + rating]) : null,
                hub.el('span', { class: 'quick-book' }, [quickBookLabel])
            ].filter(Boolean)),
            hub.el('div', { class: 'movie-info' }, [
                hub.el('h3', { title: title }, [title]),
                hub.el('p', {}, [meta.join(' · ') || 'Đang cập nhật'])
            ])
        ]);
    }

    function renderStateBadge(state) {
        if (state === 'UPCOMING') {
            return CinemaHub.el('span', { class: 'movie-state-badge movie-state-upcoming' }, ['Sắp chiếu']);
        }
        if (state === 'ENDED') {
            return CinemaHub.el('span', { class: 'movie-state-badge movie-state-ended' }, ['Ngừng chiếu']);
        }
        return null;
    }

    function formatDate(value) {
        if (!value) return '';
        try {
            const d = new Date(value);
            if (isNaN(d.getTime())) return String(value);
            const pad = function (n) { return n < 10 ? '0' + n : '' + n; };
            return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear();
        } catch (e) {
            return String(value);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();