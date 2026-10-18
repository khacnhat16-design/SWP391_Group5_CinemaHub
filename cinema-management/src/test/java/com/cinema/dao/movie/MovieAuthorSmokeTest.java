package com.cinema.dao.movie;

import com.cinema.dao.movie.MovieDAO;
import com.cinema.model.movie.Movie;

import com.cinema.infrastructure.database.DBContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Smoke test runtime cho Movie DAO với field author mới.
 *
 * <p>Mục tiêu: xác minh rằng:
 * <ul>
 *   <li>{@link Movie} có field {@code author} + getter/setter</li>
 *   <li>DAO insert/update đọc/ghi đúng cột {@code author}</li>
 *   <li>mapRow đọc đúng cột {@code author}</li>
 * </ul>
 *
 * <p>Test chạy trực tiếp với SQL Server local (DBContext). Nếu DB không khả dụng,
 * test sẽ skip (return) thay vì fail để tránh vỡ CI khi không có SQL Server.
 *
 * <p>Chạy: {@code mvn -Dtest=MovieAuthorSmokeTest test}
 */
public class MovieAuthorSmokeTest {

    private static boolean dbAvailable() {
        try (Connection conn = DBContext.getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (Exception e) {
            System.out.println("[smoke] DB unavailable, skipping: " + e.getMessage());
            return false;
        }
    }

    private static boolean columnExists() {
        String sql = "SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.movie') AND name = 'author'";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (Exception e) {
            System.out.println("[smoke] Cannot inspect schema: " + e.getMessage());
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. Reflection: đảm bảo Movie có field/ctor author
        Field authorField = Movie.class.getDeclaredField("author");
        authorField.setAccessible(true);

        Method getter = Movie.class.getMethod("author");
        Method setter = Movie.class.getMethod("setAuthor", String.class);

        Movie m = new Movie();
        setter.invoke(m, "Christopher Nolan");
        if (!"Christopher Nolan".equals(getter.invoke(m))) {
            throw new AssertionError("Movie.author getter/setter broken");
        }
        System.out.println("[smoke] Movie.author reflection OK");

        // 2. DB-level: nếu SQL Server sẵn sàng + cột đã được migrate, insert+update+findById
        if (!dbAvailable()) {
            System.out.println("[smoke] DONE (skipped DB roundtrip)");
            return;
        }
        if (!columnExists()) {
            System.out.println("[smoke] Column dbo.movie.author missing — apply migration_movie_author.sql first.");
            return;
        }

        MovieDAO dao = new MovieDAO();

        Movie in = new Movie();
        in.setTitle("Smoke Test Movie " + System.currentTimeMillis());
        in.setDurationMin(120);
        in.setGenre("Smoke");
        in.setRating("P");
        in.setReleaseDate(LocalDate.now());
        in.setEndDate(LocalDate.now().plusDays(30));
        in.setPosterUrl("");
        in.setDescription("Smoke test description");
        in.setAuthor("Tác giả Smoke");
        in.setStatus(Movie.STATUS_DRAFT);

        dao.insert(in);
        Long id = in.id();
        if (id == null || id <= 0) throw new AssertionError("Insert failed (no generated id)");
        System.out.println("[smoke] Inserted id=" + id);

        Optional<Movie> reload = dao.findById(id);
        if (reload.isEmpty()) throw new AssertionError("findById returned empty");
        Movie r = reload.get();
        if (!"Tác giả Smoke".equals(r.author())) {
            throw new AssertionError("Author not persisted. Got: " + r.author());
        }
        System.out.println("[smoke] findById author OK: " + r.author());

        r.setAuthor("Tác giả Updated");
        dao.update(r);

        Optional<Movie> reload2 = dao.findById(id);
        if (reload2.isEmpty()) throw new AssertionError("findById #2 returned empty");
        if (!"Tác giả Updated".equals(reload2.get().author())) {
            throw new AssertionError("Update author failed. Got: " + reload2.get().author());
        }
        System.out.println("[smoke] Update author OK: " + reload2.get().author());

        // Cleanup
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM dbo.movie WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        System.out.println("[smoke] Cleanup OK");
        System.out.println("[smoke] ALL PASSED");
    }
}
