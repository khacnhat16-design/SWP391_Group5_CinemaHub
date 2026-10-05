package com.cinema.movie;

import dal.DBContext;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Data access for movie table (Req 2.1-2.6). */
public class MovieDAO {

    /**
     * Insert new movie; sets generated id back on the entity.
     */
    public void insert(Movie movie) throws Exception {
        String sql = """
            INSERT INTO dbo.movie (title, duration_min, genre, rating, release_date, end_date,
                                   poster_url, description, author, status, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """;

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, movie);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) movie.setId(rs.getLong(1));
                    }
                }
                replaceGenres(conn, movie.id(), movie.genre());
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Find movie by ID.
     */
    public Optional<Movie> findById(Long id) throws Exception {
        String sql = """
            SELECT id, title, duration_min, genre, rating, release_date, end_date,
                   poster_url, description, author, status, version
            FROM dbo.movie WHERE id = ?
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** Find a movie only when it is scheduled or allocated for one of the supplied branches. */
    public Optional<Movie> findByIdForBranches(Long id, Set<Long> branchIds) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, duration_min, genre, rating, release_date, end_date,
                       poster_url, description, author, status, version
                FROM dbo.movie
                WHERE id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(id);
        appendBranchScope(sql, params, branchIds);
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Find all movies (Admin catalog view), newest release first.
     */
    public List<Movie> findAll() throws Exception {
        String sql = """
            SELECT id, title, duration_min, genre, rating, release_date, end_date,
                   poster_url, description, author, status, version
            FROM dbo.movie ORDER BY release_date DESC, id DESC
            """;

        List<Movie> movies = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                movies.add(mapRow(rs));
            }
        }
        return movies;
    }

    /**
     * Paginated search for movies with optional filters.
     * <p>
     * All parameters are optional; null/blank means "no filter". Sort columns are whitelisted
     * (title, release_date, rating, duration_min, created_at, id) and directions are limited to
     * ASC / DESC to avoid SQL injection.
     *
     * @return list of movies on the requested page
     */
    public List<Movie> search(MovieQuery q) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, duration_min, genre, rating, release_date, end_date,
                       poster_url, description, author, status, version
                FROM dbo.movie
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (q.search != null && !q.search.isBlank()) {
            sql.append(" AND (LOWER(title) LIKE ? OR LOWER(genre) LIKE ? OR LOWER(description) LIKE ?)");
            String like = "%" + q.search.trim().toLowerCase() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (q.status != null && !q.status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(q.status.trim());
        }
        if (q.genre != null && !q.genre.isBlank()) {
            sql.append("""
                    AND EXISTS (SELECT 1 FROM dbo.movie_genre mg
                                JOIN dbo.genre g ON g.id = mg.genre_id
                                WHERE mg.movie_id = dbo.movie.id AND g.name = ? AND g.is_active = 1)
                    """);
            params.add(q.genre.trim());
        }
        if (q.rating != null && !q.rating.isBlank()) {
            sql.append(" AND rating = ?");
            params.add(q.rating.trim());
        }
        appendBranchScope(sql, params, q.branchIds);
        sql.append(" ORDER BY ").append(whitelistOrderBy(q.sortBy, q.sortDir)).append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(q.offset);
        params.add(q.limit);

        List<Movie> movies = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movies.add(mapRow(rs));
                }
            }
        }
        return movies;
    }

    /** Count movies matching {@link MovieQuery} (ignoring pagination). */
    public long countSearch(MovieQuery q) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dbo.movie WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (q.search != null && !q.search.isBlank()) {
            sql.append(" AND (LOWER(title) LIKE ? OR LOWER(genre) LIKE ? OR LOWER(description) LIKE ?)");
            String like = "%" + q.search.trim().toLowerCase() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (q.status != null && !q.status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(q.status.trim());
        }
        if (q.genre != null && !q.genre.isBlank()) {
            sql.append("""
                    AND EXISTS (SELECT 1 FROM dbo.movie_genre mg
                                JOIN dbo.genre g ON g.id = mg.genre_id
                                WHERE mg.movie_id = dbo.movie.id AND g.name = ? AND g.is_active = 1)
                    """);
            params.add(q.genre.trim());
        }
        if (q.rating != null && !q.rating.isBlank()) {
            sql.append(" AND rating = ?");
            params.add(q.rating.trim());
        }
        appendBranchScope(sql, params, q.branchIds);
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private static void appendBranchScope(StringBuilder sql, List<Object> params,
                                          Set<Long> branchIds) {
        if (branchIds == null) return;
        if (branchIds.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        List<Long> scopedBranches = new ArrayList<>(branchIds);
        String placeholders = String.join(",", Collections.nCopies(scopedBranches.size(), "?"));
        sql.append("""
                 AND (
                     EXISTS (SELECT 1 FROM dbo.showtime s
                             WHERE s.movie_id = dbo.movie.id
                               AND s.status <> 'CANCELLED'
                               AND s.branch_id IN (%s))
                     OR EXISTS (SELECT 1 FROM dbo.showtime_allocation sa
                                WHERE sa.movie_id = dbo.movie.id
                                  AND sa.branch_id IN (%s))
                 )
                """.formatted(placeholders, placeholders));
        params.addAll(scopedBranches);
        params.addAll(scopedBranches);
    }

    private static String whitelistOrderBy(String sortBy, String sortDir) {
        // Client có thể truyền sortBy/sortDir tùy ý → chỉ whitelist các giá trị đã
        // biết để chặn SQL injection; mọi giá trị ngoài whitelist sẽ bị map về default.
        String col;
        if (sortBy == null) {
            col = "release_date";
        } else {
            switch (sortBy.trim().toLowerCase()) {
                case "title" -> col = "title";
                case "rating" -> col = "rating";
                case "duration_min", "duration" -> col = "duration_min";
                case "id" -> col = "id";
                case "status" -> col = "status";
                case "release_date" -> col = "release_date";
                default -> col = "release_date";
            }
        }
        String dir = (sortDir != null && "asc".equalsIgnoreCase(sortDir.trim())) ? "ASC" : "DESC";
        return col + " " + dir + ", id DESC";
    }

    /**
     * Filter/sort/pagination parameters for {@link #search(MovieQuery)} and {@link #countSearch(MovieQuery)}.
     */
    public static final class MovieQuery {
        public String search;
        public String status;
        public String genre;
        public String rating;
        public String sortBy;
        public String sortDir;
        public int offset;
        public int limit;
        /** Null means unscoped (Admin); empty means no assigned branches. */
        public Set<Long> branchIds;
    }

    /** Public catalog: only published movies currently inside their screening window. */
    public List<Movie> findPublished(LocalDate date) throws Exception {
        String sql = """
            SELECT id, title, duration_min, genre, rating, release_date, end_date,
                   poster_url, description, author, status, version
            FROM dbo.movie
            WHERE status = 'PUBLISHED' AND release_date <= ? AND end_date >= ?
            ORDER BY release_date DESC, id DESC
            """;

        List<Movie> movies = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            ps.setDate(2, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movies.add(mapRow(rs));
                }
            }
        }
        return movies;
    }

    /**
     * Find movies selectable for scheduling (Req 2.5): PUBLISHED and
     * within the effective date range [release_date, end_date].
     * Used by Branch Manager/Branch Staff when creating showtimes.
     */
    public List<Movie> findSelectableForScheduling(LocalDate date) throws Exception {
        return findSelectableForScheduling(date, null);
    }

    /** Scheduling catalog scoped to movies associated with the manager's branches. */
    public List<Movie> findSelectableForScheduling(LocalDate date, Set<Long> branchIds)
            throws Exception {
        String sql = """
            SELECT id, title, duration_min, genre, rating, release_date, end_date,
                   poster_url, description, author, status, version
            FROM dbo.movie
            """;
        StringBuilder scopedSql = new StringBuilder(sql)
                .append(" WHERE status = 'PUBLISHED' AND release_date <= ? AND end_date >= ?");
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(date));
        params.add(Date.valueOf(date));
        appendBranchScope(scopedSql, params, branchIds);
        scopedSql.append(" ORDER BY release_date DESC, id DESC");

        List<Movie> movies = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(scopedSql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movies.add(mapRow(rs));
                }
            }
        }
        return movies;
    }

    /**
     * Update editable catalog fields. Duration/rating changes are guarded in
     * MovieService against future showtimes (Req 2.3); the DAO stays unconditional.
     * Bumps optimistic-lock version.
     */
    public void update(Movie movie) throws Exception {
        String sql = """
            UPDATE dbo.movie
            SET title = ?, duration_min = ?, genre = ?, rating = ?, release_date = ?, end_date = ?,
                poster_url = ?, description = ?, author = ?, status = ?, version = version + 1
            WHERE id = ?
            """;

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bind(ps, movie);
                    ps.setLong(11, movie.id());
                    ps.executeUpdate();
                }
                replaceGenres(conn, movie.id(), movie.genre());
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static void replaceGenres(Connection conn, Long movieId, String genreNames) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM dbo.movie_genre WHERE movie_id = ?")) {
            ps.setLong(1, movieId);
            ps.executeUpdate();
        }
        for (String name : genreNames.split(",\\s*")) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO dbo.movie_genre(movie_id, genre_id)
                    SELECT ?, id FROM dbo.genre WHERE name = ? AND is_active = 1
                    """)) {
                ps.setLong(1, movieId);
                ps.setString(2, name);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("Thể loại không tồn tại hoặc đã bị vô hiệu hóa: " + name);
                }
            }
        }
    }

    /**
     * Update only the status column (publish/archive transitions).
     */
    public void updateStatus(Long movieId, String status) throws Exception {
        String sql = "UPDATE dbo.movie SET status = ?, version = version + 1 WHERE id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, movieId);
            ps.executeUpdate();
        }
    }

    /**
     * List future, not-yet-ended showtimes referencing this movie (Req 2.3, 2.4).
     * Used to block duration/rating edits and delete/deactivate while the movie
     * still has upcoming screenings; also returned in the rejection payload so
     * Admin sees which showtimes are affected.
     */
    public List<FutureShowtimeRef> findFutureShowtimes(Long movieId) throws Exception {
        String sql = """
            SELECT s.id, s.start_time, s.end_time, s.status, s.branch_id, b.name AS branch_name
            FROM dbo.showtime s
            JOIN dbo.branch b ON b.id = s.branch_id
            WHERE s.movie_id = ? AND s.status = 'OPEN' AND s.start_time > SYSUTCDATETIME()
            ORDER BY s.start_time
            """;

        List<FutureShowtimeRef> refs = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    refs.add(new FutureShowtimeRef(
                        rs.getLong("id"),
                        rs.getObject("start_time", java.time.LocalDateTime.class),
                        rs.getObject("end_time", java.time.LocalDateTime.class),
                        rs.getString("status"),
                        rs.getLong("branch_id"),
                        rs.getString("branch_name")
                    ));
                }
            }
        }
        return refs;
    }

    private void bind(PreparedStatement ps, Movie movie) throws Exception {
        ps.setString(1, movie.title());
        ps.setInt(2, movie.durationMin());
        ps.setString(3, movie.genre());
        ps.setString(4, movie.rating());
        ps.setDate(5, Date.valueOf(movie.releaseDate()));
        ps.setDate(6, Date.valueOf(movie.endDate()));
        ps.setString(7, movie.posterUrl());
        ps.setString(8, movie.description());
        ps.setString(9, movie.author());
        ps.setString(10, movie.status());
    }

    private Movie mapRow(ResultSet rs) throws Exception {
        Movie movie = new Movie();
        movie.setId(rs.getLong("id"));
        movie.setTitle(rs.getString("title"));
        movie.setDurationMin(rs.getInt("duration_min"));
        movie.setGenre(rs.getString("genre"));
        movie.setRating(rs.getString("rating"));

        Date release = rs.getDate("release_date");
        if (release != null) movie.setReleaseDate(release.toLocalDate());

        Date end = rs.getDate("end_date");
        if (end != null) movie.setEndDate(end.toLocalDate());

        movie.setPosterUrl(rs.getString("poster_url"));
        movie.setDescription(rs.getString("description"));
        movie.setAuthor(rs.getString("author"));
        movie.setStatus(rs.getString("status"));
        movie.setVersion(rs.getInt("version"));
        return movie;
    }

    /**
     * Lightweight reference to a future showtime (for rejection messages, Req 2.4).
     */
    public record FutureShowtimeRef(long showtimeId, java.time.LocalDateTime startTime,
                                    java.time.LocalDateTime endTime, String status,
                                    long branchId, String branchName) {
    }
}
