package com.cinema.review;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** DAO for dbo.review (RSC-2). */
public class ReviewDAO {

    public Optional<Review> findByUserAndMovie(long userId, long movieId) throws Exception {
        String sql = """
                SELECT r.id, r.movie_id, r.user_id, u.full_name, r.rating, r.comment,
                       r.created_at, r.updated_at
                FROM dbo.review r
                JOIN dbo.user_account u ON u.id = r.user_id
                WHERE r.user_id = ? AND r.movie_id = ?
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    /** Upsert by UNIQUE(movie_id, user_id). */
    public void upsert(long userId, long movieId, int rating, String comment) throws Exception {
        String sql = """
                MERGE dbo.review AS target
                USING (SELECT ? AS user_id, ? AS movie_id) AS src
                ON target.user_id = src.user_id AND target.movie_id = src.movie_id
                WHEN MATCHED THEN
                    UPDATE SET rating = ?, comment = ?, updated_at = SYSUTCDATETIME()
                WHEN NOT MATCHED THEN
                    INSERT (movie_id, user_id, rating, comment)
                    VALUES (?, ?, ?, ?);
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, movieId);
            ps.setInt(3, rating);
            ps.setString(4, comment);
            ps.setLong(5, movieId);
            ps.setLong(6, userId);
            ps.setInt(7, rating);
            ps.setString(8, comment);
            ps.executeUpdate();
        }
    }

    public List<Review> findByMovie(long movieId, int offset, int limit) throws Exception {
        String sql = """
                SELECT r.id, r.movie_id, r.user_id, u.full_name, r.rating, r.comment,
                       r.created_at, r.updated_at
                FROM dbo.review r
                JOIN dbo.user_account u ON u.id = r.user_id
                WHERE r.movie_id = ?
                ORDER BY r.created_at DESC, r.id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;
        List<Review> out = new ArrayList<>();
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            ps.setInt(2, offset);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public long countByMovie(long movieId) throws Exception {
        String sql = "SELECT COUNT(*) FROM dbo.review WHERE movie_id = ?";
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    /** Returns average rating (1 decimal) or 0 when no reviews. */
    public double averageByMovie(long movieId) throws Exception {
        String sql = "SELECT CAST(AVG(CAST(rating AS DECIMAL(3,2))) AS DECIMAL(3,1)) FROM dbo.review WHERE movie_id = ?";
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double v = rs.getDouble(1);
                    if (rs.wasNull()) return 0d;
                    return Math.round(v * 10d) / 10d;
                }
            }
        }
        return 0d;
    }

    /**
     * Trả về Map movieId → ReviewStats{avg, count} cho một tập movieIds trong 1 query.
     * Tránh N+1 khi manager muốn xem avg rating của cả danh sách phim.
     */
    public java.util.Map<Long, ReviewStats> statsByMovieIds(java.util.Collection<Long> movieIds) throws Exception {
        java.util.Map<Long, ReviewStats> out = new java.util.HashMap<>();
        if (movieIds == null || movieIds.isEmpty()) return out;
        StringBuilder sql = new StringBuilder("""
                SELECT movie_id,
                       CAST(AVG(CAST(rating AS DECIMAL(3,2))) AS DECIMAL(3,1)) AS avg_rating,
                       COUNT(*) AS cnt
                FROM dbo.review
                WHERE movie_id IN (
                """);
        for (Long id : movieIds) sql.append("?,");
        sql.setLength(sql.length() - 1);
        sql.append(") GROUP BY movie_id");
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long id : movieIds) ps.setLong(idx++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long mid = rs.getLong("movie_id");
                    double avg = rs.getDouble("avg_rating");
                    if (rs.wasNull()) avg = 0d;
                    long cnt = rs.getLong("cnt");
                    out.put(mid, new ReviewStats(Math.round(avg * 10d) / 10d, cnt));
                }
            }
        }
        return out;
    }

    /** Tuple (avg, count) cho 1 phim. */
    public static final class ReviewStats {
        public final double average;
        public final long count;
        public ReviewStats(double average, long count) {
            this.average = average;
            this.count = count;
        }
    }

    /** Returns true if deleted, false if not found / not owner. */
    public boolean deleteByIdAndUser(long id, long userId) throws Exception {
        String sql = "DELETE FROM dbo.review WHERE id = ? AND user_id = ?";
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    // ---------------------------------------------------------------------
    // Manager view: tất cả review của phim thuộc chi nhánh branch_id
    // (thông qua bảng dbo.showtime.branch_id).
    // ---------------------------------------------------------------------
    private static final String SELECT_REVIEW_FIELDS =
            "r.id, r.movie_id, r.user_id, u.full_name, r.rating, r.comment, " +
            "r.created_at, r.updated_at, m.title AS movie_title";

    /**
     * Một review được tính cho branch nếu tồn tại ít nhất một showtime của
     * phim đó tại branch (kể cả OPEN/ENDED/CANCELLED). DISTINCT để tránh
     * trùng review khi một phim có nhiều suất tại cùng branch.
     */
    public List<Review> findByBranch(long branchId, int offset, int limit) throws Exception {
        String sql = """
                SELECT DISTINCT %s
                FROM dbo.review r
                JOIN dbo.user_account u ON u.id = r.user_id
                JOIN dbo.movie m ON m.id = r.movie_id
                WHERE EXISTS (
                    SELECT 1 FROM dbo.showtime s
                    WHERE s.movie_id = r.movie_id AND s.branch_id = ?
                )
                ORDER BY r.created_at DESC, r.id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """.formatted(SELECT_REVIEW_FIELDS);
        List<Review> out = new ArrayList<>();
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setInt(2, offset);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapWithMovie(rs));
            }
        }
        return out;
    }

    public List<Review> findByBranchAndMovie(long branchId, long movieId, int offset, int limit) throws Exception {
        String sql = """
                SELECT r.id, r.movie_id, r.user_id, u.full_name, r.rating, r.comment,
                       r.created_at, r.updated_at, m.title AS movie_title
                FROM dbo.review r
                JOIN dbo.user_account u ON u.id = r.user_id
                JOIN dbo.movie m ON m.id = r.movie_id
                WHERE r.movie_id = ?
                  AND EXISTS (
                      SELECT 1 FROM dbo.showtime s
                      WHERE s.movie_id = r.movie_id
                        AND s.branch_id = ?
                        AND s.status <> 'CANCELLED'
                  )
                ORDER BY r.created_at DESC, r.id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;
        List<Review> out = new ArrayList<>();
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            ps.setLong(2, branchId);
            ps.setInt(3, offset);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapWithMovie(rs));
            }
        }
        return out;
    }

    public List<Map<String, Object>> movieSummariesByBranch(long branchId) throws Exception {
        String sql = """
                SELECT m.id AS movie_id, m.title AS movie_title,
                       COALESCE(CAST(AVG(CAST(r.rating AS DECIMAL(3,2))) AS DECIMAL(3,1)), 0) AS average_rating,
                       COUNT(r.id) AS review_count
                FROM dbo.movie m
                LEFT JOIN dbo.review r ON r.movie_id = m.id
                WHERE EXISTS (
                    SELECT 1 FROM dbo.showtime s
                    WHERE s.movie_id = m.id
                      AND s.branch_id = ?
                      AND s.status <> 'CANCELLED'
                )
                GROUP BY m.id, m.title
                ORDER BY m.title, m.id
                """;
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("movieId", rs.getLong("movie_id"));
                    item.put("movieTitle", rs.getString("movie_title"));
                    item.put("average", rs.getDouble("average_rating"));
                    item.put("total", rs.getLong("review_count"));
                    out.add(item);
                }
            }
        }
        return out;
    }

    public long countByBranchAndMovie(long branchId, long movieId) throws Exception {
        String sql = """
                SELECT COUNT(*)
                FROM dbo.review r
                WHERE r.movie_id = ?
                  AND EXISTS (
                      SELECT 1 FROM dbo.showtime s
                      WHERE s.movie_id = r.movie_id
                        AND s.branch_id = ?
                        AND s.status <> 'CANCELLED'
                  )
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            ps.setLong(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long countByBranch(long branchId) throws Exception {
        String sql = """
                SELECT COUNT(DISTINCT r.id)
                FROM dbo.review r
                WHERE EXISTS (
                    SELECT 1 FROM dbo.showtime s
                    WHERE s.movie_id = r.movie_id AND s.branch_id = ?
                )
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    public double averageByBranch(long branchId) throws Exception {
        String sql = """
                SELECT CAST(AVG(CAST(r.rating AS DECIMAL(3,2))) AS DECIMAL(3,1))
                FROM dbo.review r
                WHERE EXISTS (
                    SELECT 1 FROM dbo.showtime s
                    WHERE s.movie_id = r.movie_id AND s.branch_id = ?
                )
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double v = rs.getDouble(1);
                    if (rs.wasNull()) return 0d;
                    return Math.round(v * 10d) / 10d;
                }
            }
        }
        return 0d;
    }

    /**
     * Map movie_id → ReviewStats cho tất cả phim đã/đang chiếu tại branch.
     */
    public java.util.Map<Long, ReviewStats> statsByBranch(long branchId) throws Exception {
        java.util.Map<Long, ReviewStats> out = new java.util.HashMap<>();
        String sql = """
                SELECT r.movie_id,
                       CAST(AVG(CAST(r.rating AS DECIMAL(3,2))) AS DECIMAL(3,1)) AS avg_rating,
                       COUNT(DISTINCT r.id) AS cnt
                FROM dbo.review r
                WHERE EXISTS (
                    SELECT 1 FROM dbo.showtime s
                    WHERE s.movie_id = r.movie_id AND s.branch_id = ?
                )
                GROUP BY r.movie_id
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long mid = rs.getLong("movie_id");
                    double avg = rs.getDouble("avg_rating");
                    if (rs.wasNull()) avg = 0d;
                    long cnt = rs.getLong("cnt");
                    out.put(mid, new ReviewStats(Math.round(avg * 10d) / 10d, cnt));
                }
            }
        }
        return out;
    }

    public Optional<Review> findById(long id) throws Exception {
        String sql = """
                SELECT r.id, r.movie_id, r.user_id, u.full_name, r.rating, r.comment,
                       r.created_at, r.updated_at
                FROM dbo.review r
                JOIN dbo.user_account u ON u.id = r.user_id
                WHERE r.id = ?
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    private Review map(ResultSet rs) throws Exception {
        Review r = new Review();
        r.setId(rs.getLong("id"));
        r.setMovieId(rs.getLong("movie_id"));
        r.setUserId(rs.getLong("user_id"));
        r.setUserFullName(rs.getString("full_name"));
        r.setRating(rs.getInt("rating"));
        r.setComment(rs.getString("comment"));
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ca != null) r.setCreatedAt(ca.toLocalDateTime());
        if (ua != null) r.setUpdatedAt(ua.toLocalDateTime());
        return r;
    }

    /** Same as {@link #map} but also reads movie_title (manager view). */
    private Review mapWithMovie(ResultSet rs) throws Exception {
        Review r = map(rs);
        try { r.setMovieTitle(rs.getString("movie_title")); }
        catch (java.sql.SQLException ignored) { /* column not present */ }
        return r;
    }
}
