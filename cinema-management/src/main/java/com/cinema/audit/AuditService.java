package com.cinema.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Ghi nhật ký thay đổi trong connection/transaction do service cung cấp. */
public final class AuditService {
    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(AuditService.class.getName());

    private static final String INSERT = """
            INSERT INTO audit_log(actor_id, action, entity_type, entity_id,
                                  before_json, after_json, result)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    public void record(Connection connection, Long actorId, String action, String entityType,
                       Long entityId, String beforeJson, String afterJson, String result) throws SQLException {
        requireText(action, "action");
        requireText(entityType, "entityType");
        requireText(result, "result");
        if (connection == null) throw new IllegalArgumentException("connection is required");
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            if (actorId == null) statement.setNull(1, java.sql.Types.BIGINT);
            else statement.setLong(1, actorId);
            statement.setString(2, action);
            statement.setString(3, entityType);
            if (entityId == null) statement.setNull(4, java.sql.Types.BIGINT);
            else statement.setLong(4, entityId);
            statement.setString(5, beforeJson);
            statement.setString(6, afterJson);
            statement.setString(7, result);
            statement.executeUpdate();
        }
    }

    /**
     * Ghi audit best-effort trên connection riêng (Req 15.3): dùng cho các transition
     * không chạy trong transaction của caller hoặc ghi nhận thất bại nghiệp vụ
     * (result=FAILURE) — dữ liệu nghiệp vụ không đổi nhưng sự kiện vẫn được truy vết.
     * Lỗi ghi audit chỉ log, không phá luồng chính (Req 15.4).
     */
    public void recordSafely(Long actorId, String action, String entityType,
                             Long entityId, String beforeJson, String afterJson, String result) {
        try (Connection connection = dal.DBContext.getConnection()) {
            record(connection, actorId, action, entityType, entityId, beforeJson, afterJson, result);
        } catch (Exception e) {
            logger.warning("Không ghi được audit " + action + "/" + entityType
                    + " — bỏ qua, không ảnh hưởng luồng chính: " + e.getMessage());
        }
    }

    /** Kết quả chuẩn (CK_audit_result: SUCCESS/FAILURE). */
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
