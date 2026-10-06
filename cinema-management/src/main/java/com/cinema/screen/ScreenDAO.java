package com.cinema.screen;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for screen table (Req 3.1-3.6). */
public class ScreenDAO {

    /** Insert new screen; sets generated id back on the entity. */
    public void insert(Screen screen) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            insert(conn, screen);
        }
    }

    public void insert(Connection conn, Screen screen) throws Exception {
        String sql = """
            INSERT INTO dbo.screen (branch_id, code, name, row_count, col_count, status)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, screen.branchId());
            ps.setString(2, screen.code());
            ps.setString(3, screen.name());
            ps.setInt(4, screen.rowCount());
            ps.setInt(5, screen.colCount());
            ps.setString(6, screen.status());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    screen.setId(rs.getLong(1));
                }
            }
        }
    }

    public Optional<Screen> findById(Long id) throws Exception {
        String sql = """
            SELECT id, branch_id, code, name, row_count, col_count, status
            FROM dbo.screen WHERE id = ?
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

    /** List screens of a branch. */
    public List<Screen> findByBranch(long branchId) throws Exception {
        String sql = """
            SELECT id, branch_id, code, name, row_count, col_count, status
            FROM dbo.screen WHERE branch_id = ? ORDER BY code
            """;

        List<Screen> screens = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    screens.add(mapRow(rs));
                }
            }
        }
        return screens;
    }

    /** List all screens, ordered by branch and code (Admin-only controller path). */
    public List<Screen> findAll() throws Exception {
        String sql = """
            SELECT id, branch_id, code, name, row_count, col_count, status
            FROM dbo.screen ORDER BY branch_id, code
            """;

        List<Screen> screens = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                screens.add(mapRow(rs));
            }
        }
        return screens;
    }

    /** Find screen by (branch, code) for uniqueness checks (Req 3.1). */
    public Optional<Screen> findByBranchAndCode(long branchId, String code) throws Exception {
        String sql = """
            SELECT id, branch_id, code, name, row_count, col_count, status
            FROM dbo.screen WHERE branch_id = ? AND code = ?
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** Update editable fields (dimensions/seat map guarded in ScreenService). */
    public void update(Screen screen) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            update(conn, screen);
        }
    }

    public void update(Connection conn, Screen screen) throws Exception {
        String sql = """
            UPDATE dbo.screen
            SET code = ?, name = ?, row_count = ?, col_count = ?, status = ?
            WHERE id = ?
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, screen.code());
            ps.setString(2, screen.name());
            ps.setInt(3, screen.rowCount());
            ps.setInt(4, screen.colCount());
            ps.setString(5, screen.status());
            ps.setLong(6, screen.id());
            ps.executeUpdate();
        }
    }

    private Screen mapRow(ResultSet rs) throws Exception {
        Screen screen = new Screen();
        screen.setId(rs.getLong("id"));
        screen.setBranchId(rs.getLong("branch_id"));
        screen.setCode(rs.getString("code"));
        screen.setName(rs.getString("name"));
        screen.setRowCount(rs.getInt("row_count"));
        screen.setColCount(rs.getInt("col_count"));
        screen.setStatus(rs.getString("status"));
        return screen;
    }
}
