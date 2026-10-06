package com.cinema.screen;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Data access for seat table (Req 3.1-3.4). */
public class SeatDAO {

    /** Insert one seat; sets generated id back on the entity. */
    public void insert(Seat seat) throws Exception {
        String sql = """
            INSERT INTO dbo.seat (screen_id, row_label, col_no, seat_type, status)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, seat.screenId());
            ps.setString(2, seat.rowLabel());
            ps.setInt(3, seat.colNo());
            ps.setString(4, seat.seatType());
            ps.setString(5, seat.status());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    seat.setId(rs.getLong(1));
                }
            }
        }
    }

    /** Bulk insert seats of a seat map (one batch, single connection). */
    public void insertAll(long screenId, List<Seat> seats) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertAll(conn, screenId, seats);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public void insertAll(Connection conn, long screenId, List<Seat> seats) throws Exception {
        String sql = """
            INSERT INTO dbo.seat (screen_id, row_label, col_no, seat_type, status)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Seat seat : seats) {
                ps.setLong(1, screenId);
                ps.setString(2, seat.rowLabel());
                ps.setInt(3, seat.colNo());
                ps.setString(4, seat.seatType());
                ps.setString(5, seat.status());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Optional<Seat> findById(Long id) throws Exception {
        String sql = """
            SELECT id, screen_id, row_label, col_no, seat_type, status
            FROM dbo.seat WHERE id = ?
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

    /** Seat map of a screen ordered row then column. */
    public List<Seat> findByScreen(long screenId) throws Exception {
        String sql = """
            SELECT id, screen_id, row_label, col_no, seat_type, status
            FROM dbo.seat WHERE screen_id = ? ORDER BY row_label, col_no
            """;

        List<Seat> seats = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, screenId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    seats.add(mapRow(rs));
                }
            }
        }
        return seats;
    }

    public List<Seat> findByScreen(Connection conn, long screenId, boolean lock) throws Exception {
        String sql = """
            SELECT id, screen_id, row_label, col_no, seat_type, status
            FROM dbo.seat
            """ + (lock ? " WITH (UPDLOCK, HOLDLOCK)" : "")
                + " WHERE screen_id = ? ORDER BY row_label, col_no";
        List<Seat> seats = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, screenId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) seats.add(mapRow(rs));
            }
        }
        return seats;
    }

    /** Synchronize positions without deleting seat rows referenced by bookings. */
    public void synchronize(Connection conn, long screenId, List<Seat> desired) throws Exception {
        Map<String, Seat> currentByPosition = new HashMap<>();
        for (Seat seat : findByScreen(conn, screenId, true)) {
            currentByPosition.put(positionKey(seat.rowLabel(), seat.colNo()), seat);
        }
        Set<String> desiredPositions = new java.util.HashSet<>();
        String updateSql = """
            UPDATE dbo.seat SET seat_type = ?, status = ?
            WHERE id = ?
            """;
        String insertSql = """
            INSERT INTO dbo.seat (screen_id, row_label, col_no, seat_type, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """;
        try (PreparedStatement update = conn.prepareStatement(updateSql);
             PreparedStatement insert = conn.prepareStatement(insertSql)) {
            for (Seat requested : desired) {
                String key = positionKey(requested.rowLabel(), requested.colNo());
                desiredPositions.add(key);
                Seat current = currentByPosition.get(key);
                if (current == null) {
                    insert.setLong(1, screenId);
                    insert.setString(2, requested.rowLabel());
                    insert.setInt(3, requested.colNo());
                    insert.setString(4, requested.seatType());
                    insert.addBatch();
                } else {
                    update.setString(1, requested.seatType());
                    update.setString(2, "ACTIVE");
                    update.setLong(3, current.id());
                    update.addBatch();
                }
            }

            for (Seat current : currentByPosition.values()) {
                if (!desiredPositions.contains(positionKey(current.rowLabel(), current.colNo()))) {
                    update.setString(1, current.seatType());
                    update.setString(2, "INACTIVE");
                    update.setLong(3, current.id());
                    update.addBatch();
                }
            }
            update.executeBatch();
            insert.executeBatch();
        }
    }

    public void initializeFutureShowtimeSeats(Connection conn, long screenId) throws Exception {
        String sql = """
            INSERT INTO dbo.showtime_seat (showtime_id, seat_id, status)
            SELECT st.id, se.id, 'AVAILABLE'
            FROM dbo.showtime st
            JOIN dbo.seat se ON se.screen_id = st.screen_id AND se.status = 'ACTIVE'
            WHERE st.screen_id = ? AND st.status = 'OPEN'
              AND st.start_time > SYSUTCDATETIME()
              AND NOT EXISTS (
                  SELECT 1 FROM dbo.showtime_seat existing
                  WHERE existing.showtime_id = st.id AND existing.seat_id = se.id
              )
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, screenId);
            ps.executeUpdate();
        }
    }

    private String positionKey(String rowLabel, int colNo) {
        return rowLabel + ":" + colNo;
    }

    /** Replace the whole seat map of a screen (guarded in ScreenService). */
    public void deleteByScreen(long screenId) throws Exception {
        String sql = "DELETE FROM dbo.seat WHERE screen_id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, screenId);
            ps.executeUpdate();
        }
    }

    private Seat mapRow(ResultSet rs) throws Exception {
        Seat seat = new Seat();
        seat.setId(rs.getLong("id"));
        seat.setScreenId(rs.getLong("screen_id"));
        seat.setRowLabel(rs.getString("row_label"));
        seat.setColNo(rs.getInt("col_no"));
        seat.setSeatType(rs.getString("seat_type"));
        seat.setStatus(rs.getString("status"));
        return seat;
    }
}
