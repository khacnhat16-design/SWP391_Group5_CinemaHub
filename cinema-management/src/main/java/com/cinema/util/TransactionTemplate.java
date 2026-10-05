package com.cinema.util;

import dal.DBContext;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Mẫu transaction thủ công (design.md §Architecture): một connection duy nhất,
 * autoCommit=false → work → commit; mọi ngoại lệ → rollback rồi ném lại,
 * đảm bảo không để lại dữ liệu nửa vời (Req 15.2, 15.4). Connection luôn được đóng.
 */
public final class TransactionTemplate {

    @FunctionalInterface
    public interface Work<T> {
        T execute(Connection connection) throws Exception;
    }

    public <T> T execute(Work<T> work) {
        Connection connection = null;
        try {
            connection = DBContext.getConnection();
            connection.setAutoCommit(false);
            T result = work.execute(connection);
            connection.commit();
            return result;
        } catch (Exception e) {
            rollbackQuietly(connection);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e instanceof SQLException sql) {
                throw new DataAccessException("Transaction thất bại và đã rollback", sql);
            }
            throw new DataAccessException("Transaction thất bại và đã rollback", e);
        } finally {
            closeQuietly(connection);
        }
    }

    /** Chạy nhiều thao tác trong cùng một transaction, không trả giá trị. */
    public void executeVoid(VoidWork work) {
        execute(connection -> {
            work.execute(connection);
            return null;
        });
    }

    @FunctionalInterface
    public interface VoidWork {
        void execute(Connection connection) throws Exception;
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException suppressed) {
            // Rollback fail thường vì connection đã timeout/closed bởi DB hoặc
            // network — lúc này lỗi gốc (exception ban đầu) mới quan trọng; nuốt
            // suppressed để caller nhìn thấy root cause rõ ràng hơn.
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Không che lỗi nghiệp vụ bằng lỗi đóng connection.
        }
    }

    /** Ngoại lệ hệ thống khi transaction lỗi (ánh xạ HTTP 500 ở tầng controller — Task 1.4/1.5). */
    public static class DataAccessException extends RuntimeException {
        public DataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
