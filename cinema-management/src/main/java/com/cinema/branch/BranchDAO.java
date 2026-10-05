package com.cinema.branch;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for branch table. */
public class BranchDAO {
    private static final String COLUMNS = """
        id, name, address, phone, status, created_at, updated_at
        """;

    /**
     * Insert new branch.
     */
    public void insert(Branch branch) throws Exception {
        String sql = """
            INSERT INTO dbo.branch
                (name, address, phone, status, created_at)
            VALUES (UPPER(LTRIM(RTRIM(?))), ?, ?, ?, SYSUTCDATETIME())
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, branch.name());
            ps.setString(2, branch.address());
            ps.setString(3, branch.phone());
            ps.setString(4, branch.status());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    branch.setId(rs.getLong(1));
                }
            }
        }
    }

    /**
     * Find branch by ID.
     */
    public Optional<Branch> findById(Long id) throws Exception {
        String sql = "SELECT " + COLUMNS + " FROM dbo.branch WHERE id = ?";

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

    /**
     * Find branch by normalized name (case-insensitive).
     */
    public Optional<Branch> findByNameNormalized(String name) throws Exception {
        String normalized = name.trim().toUpperCase();
        String sql = "SELECT " + COLUMNS + " FROM dbo.branch WHERE UPPER(LTRIM(RTRIM(name))) = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Find all ACTIVE branches.
     */
    public List<Branch> findAllActive() throws Exception {
        String sql = "SELECT " + COLUMNS + " FROM dbo.branch WHERE status = 'ACTIVE' ORDER BY name";

        List<Branch> branches = new ArrayList<>();

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                branches.add(mapRow(rs));
            }
        }

        return branches;
    }

    /**
     * Find all branches (ACTIVE and INACTIVE).
     */
    public List<Branch> findAll() throws Exception {
        String sql = "SELECT " + COLUMNS + " FROM dbo.branch ORDER BY name";

        List<Branch> branches = new ArrayList<>();

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                branches.add(mapRow(rs));
            }
        }

        return branches;
    }

    /**
     * Paginated search for branches with optional filters.
     */
    public List<Branch> search(BranchQuery q) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, address, phone, status, created_at, updated_at
                FROM dbo.branch WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (q.search != null && !q.search.isBlank()) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(address) LIKE ? OR phone LIKE ?)");
            String like = "%" + q.search.trim().toLowerCase() + "%";
            params.add(like);
            params.add(like);
            params.add("%" + q.search.trim() + "%");
        }
        if (q.status != null && !q.status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(q.status.trim());
        }
        sql.append(" ORDER BY ").append(whitelistOrderBy(q.sortBy, q.sortDir)).append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(q.offset);
        params.add(q.limit);

        List<Branch> branches = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    branches.add(mapRow(rs));
                }
            }
        }
        return branches;
    }

    /** Count branches matching {@link BranchQuery} (ignoring pagination). */
    public long countSearch(BranchQuery q) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dbo.branch WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (q.search != null && !q.search.isBlank()) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(address) LIKE ? OR phone LIKE ?)");
            String like = "%" + q.search.trim().toLowerCase() + "%";
            params.add(like);
            params.add(like);
            params.add("%" + q.search.trim() + "%");
        }
        if (q.status != null && !q.status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(q.status.trim());
        }
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

    private static String whitelistOrderBy(String sortBy, String sortDir) {
        String col;
        if (sortBy == null) {
            col = "name";
        } else {
            switch (sortBy.trim().toLowerCase()) {
                case "name" -> col = "name";
                case "status" -> col = "status";
                case "id" -> col = "id";
                case "created_at" -> col = "created_at";
                case "updated_at" -> col = "updated_at";
                default -> col = "name";
            }
        }
        String dir = (sortDir != null && "asc".equalsIgnoreCase(sortDir.trim())) ? "ASC" : "DESC";
        return col + " " + dir + ", id DESC";
    }

    /** Filter/sort/pagination parameters for branch listing. */
    public static final class BranchQuery {
        public String search;
        public String status;
        public String sortBy;
        public String sortDir;
        public int offset;
        public int limit;
    }

    /**
     * Update branch information.
     */
    public void update(Branch branch) throws Exception {
        String sql = """
            UPDATE dbo.branch
            SET name = UPPER(LTRIM(RTRIM(?))), address = ?, phone = ?,
                updated_at = SYSUTCDATETIME()
            WHERE id = ?
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, branch.name());
            ps.setString(2, branch.address());
            ps.setString(3, branch.phone());
            ps.setLong(4, branch.id());

            ps.executeUpdate();
        }
    }

    /**
     * Update branch status.
     */
    public void updateStatus(Long branchId, String status) throws Exception {
        String sql = "UPDATE dbo.branch SET status = ?, updated_at = SYSUTCDATETIME() WHERE id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, branchId);

            ps.executeUpdate();
        }
    }

    private Branch mapRow(ResultSet rs) throws Exception {
        Branch branch = new Branch(
            rs.getString("name"),
            rs.getString("address"),
            rs.getString("phone")
        );

        branch.setId(rs.getLong("id"));
        branch.setStatus(rs.getString("status"));

        Object createdObj = rs.getObject("created_at");
        if (createdObj != null) {
            // Branch model tự map created_at trong constructor → không cần set lại ở đây.
            // Nhánh if này giữ chỗ để dễ bật/tắt log hoặc ép format nếu schema đổi.
        }

        Object updatedObj = rs.getObject("updated_at");
        if (updatedObj != null) {
            branch.setUpdatedAt(((java.sql.Timestamp) updatedObj).toLocalDateTime());
        }

        return branch;
    }

}
