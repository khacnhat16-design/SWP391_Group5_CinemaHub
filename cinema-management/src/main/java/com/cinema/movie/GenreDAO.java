package com.cinema.movie;

import com.cinema.common.ServiceException;
import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GenreDAO {
    public List<Genre> findAll() throws Exception {
        String sql = "SELECT id, name FROM dbo.genre WHERE is_active = 1 ORDER BY name";
        List<Genre> genres = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) genres.add(new Genre(rs.getLong("id"), rs.getString("name")));
        }
        return genres;
    }

    public Optional<Genre> findByName(Connection conn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name FROM dbo.genre WHERE name = ? AND is_active = 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Genre(rs.getLong("id"), rs.getString("name")))
                        : Optional.empty();
            }
        }
    }

    public Optional<Genre> findById(long id) throws Exception {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name FROM dbo.genre WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Genre(id, rs.getString("name"))) : Optional.empty();
            }
        }
    }

    public Genre insert(String name) throws Exception {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO dbo.genre(name, is_active) VALUES (?, 1)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new IllegalStateException("Không tạo được thể loại phim");
                return new Genre(rs.getLong(1), name);
            }
        }
    }

    public Genre update(long id, String name) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE dbo.genre SET name = ? WHERE id = ?")) {
                    ps.setString(1, name);
                    ps.setLong(2, id);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("Thể loại không tồn tại");
                }
                refreshMovieGenreLabels(conn, id);
                conn.commit();
                return new Genre(id, name);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public void delete(long id) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT id FROM dbo.genre WITH (UPDLOCK, HOLDLOCK) WHERE id = ?")) {
                    lock.setLong(1, id);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) throw new ServiceException.NotFound("Thể loại không tồn tại");
                    }
                }
                if (isUsed(conn, id)) {
                    throw new ServiceException.BusinessRule("GENRE_IN_USE",
                            "Không thể xóa thể loại đang được gán cho phim. Hãy đổi thể loại của phim trước.");
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM dbo.genre WHERE id = ?")) {
                    ps.setLong(1, id);
                    if (ps.executeUpdate() != 1) throw new ServiceException.NotFound("Thể loại không tồn tại");
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException sqlException && sqlException.getErrorCode() == 547) {
                    throw new ServiceException.BusinessRule("GENRE_IN_USE",
                            "Không thể xóa thể loại đang được gán cho phim. Hãy đổi thể loại của phim trước.");
                }
                throw e;
            }
        }
    }

    public boolean isUsed(long id) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return isUsed(conn, id);
        }
    }

    private boolean isUsed(Connection conn, long id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TOP (1) 1 FROM dbo.movie_genre WHERE genre_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void refreshMovieGenreLabels(Connection conn, long genreId) throws Exception {
        String sql = """
                UPDATE m SET genre = labels.genre_names
                FROM dbo.movie m
                CROSS APPLY (
                    SELECT STRING_AGG(g.name, ', ') WITHIN GROUP (ORDER BY g.name) AS genre_names
                    FROM dbo.movie_genre mg
                    JOIN dbo.genre g ON g.id = mg.genre_id
                    WHERE mg.movie_id = m.id
                ) labels
                WHERE EXISTS (SELECT 1 FROM dbo.movie_genre WHERE genre_id = ? AND movie_id = m.id)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, genreId);
            ps.executeUpdate();
        }
    }
}
