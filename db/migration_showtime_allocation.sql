-- =====================================================================
-- migration_showtime_allocation.sql
-- Luồng Admin phân bổ suất chiếu cho từng chi nhánh (showtime allocation)
-- và Manager tạo Time Sheet / Room Seat.
--
-- Admin tạo "tổng suất chiếu" của từng movie và phân bổ cho từng branch.
-- Manager phải tạo đủ số suất theo phân bổ (created >= allocated).
-- Khi created > allocated → cảnh báo vượt quota.
--
-- Idempotent — an toàn khi chạy lại.
-- =====================================================================

PRINT '=== migration_showtime_allocation ===';
GO

SET XACT_ABORT ON;
SET NOCOUNT ON;

-- ============================================================
-- Bước 1: Bảng showtime_allocation
-- Lưu phân bổ Admin → Branch cho từng Movie
-- ============================================================
IF OBJECT_ID(N'dbo.showtime_allocation', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.showtime_allocation (
        id                  BIGINT IDENTITY(1,1) NOT NULL
                                CONSTRAINT PK_showtime_allocation PRIMARY KEY,
        movie_id            BIGINT NOT NULL
                                CONSTRAINT FK_showtime_allocation_movie
                                REFERENCES dbo.movie(id),
        branch_id           BIGINT NOT NULL
                                CONSTRAINT FK_showtime_allocation_branch
                                REFERENCES dbo.branch(id),
        -- Phân bổ tổng của Admin cho cặp (movie, branch)
        allocated_quantity  INT     NOT NULL,
        -- Số suất Manager đã tạo (showtime) tại branch cho movie
        created_quantity    INT     NOT NULL
                                CONSTRAINT DF_showtime_allocation_created
                                DEFAULT 0,
        -- Trạng thái nghiệp vụ (PENDING/IN_PROGRESS/COMPLETED/OVER_ALLOCATED)
        status              VARCHAR(20) NOT NULL
                                CONSTRAINT DF_showtime_allocation_status
                                DEFAULT 'PENDING',
        note                NVARCHAR(500) NULL,
        -- Người tạo / cập nhật
        created_by          BIGINT NULL
                                CONSTRAINT FK_showtime_allocation_created_by
                                REFERENCES dbo.user_account(id),
        updated_by          BIGINT NULL
                                CONSTRAINT FK_showtime_allocation_updated_by
                                REFERENCES dbo.user_account(id),
        -- Thời điểm phân bổ / Manager đạt mốc COMPLETED / cập nhật lần cuối
        allocated_at        DATETIME2(3) NOT NULL
                                CONSTRAINT DF_showtime_allocation_allocated_at
                                DEFAULT SYSUTCDATETIME(),
        completed_at        DATETIME2(3) NULL,
        last_warning_at     DATETIME2(3) NULL,
        reminder_release_date DATE NULL,
        schedule_reminder_sent_at DATETIME2(3) NULL,
        schedule_urgent_reminder_sent_at DATETIME2(3) NULL,
        created_at          DATETIME2(3) NOT NULL
                                CONSTRAINT DF_showtime_allocation_created_at
                                DEFAULT SYSUTCDATETIME(),
        updated_at          DATETIME2(3) NOT NULL
                                CONSTRAINT DF_showtime_allocation_updated_at
                                DEFAULT SYSUTCDATETIME(),
        -- Một cặp (movie, branch) chỉ có một phân bổ hiện hành
        CONSTRAINT UQ_showtime_allocation_movie_branch
            UNIQUE (movie_id, branch_id),
        CONSTRAINT CK_showtime_allocation_allocated
            CHECK (allocated_quantity >= 0),
        CONSTRAINT CK_showtime_allocation_created_qty
            CHECK (created_quantity >= 0),
        CONSTRAINT CK_showtime_allocation_status
            CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'OVER_ALLOCATED'))
    );
    PRINT '  [OK] Created dbo.showtime_allocation';
END
GO

IF COL_LENGTH(N'dbo.showtime_allocation', N'reminder_release_date') IS NULL
    ALTER TABLE dbo.showtime_allocation ADD reminder_release_date DATE NULL;
GO
IF COL_LENGTH(N'dbo.showtime_allocation', N'schedule_reminder_sent_at') IS NULL
    ALTER TABLE dbo.showtime_allocation ADD schedule_reminder_sent_at DATETIME2(3) NULL;
GO
IF COL_LENGTH(N'dbo.showtime_allocation', N'schedule_urgent_reminder_sent_at') IS NULL
    ALTER TABLE dbo.showtime_allocation ADD schedule_urgent_reminder_sent_at DATETIME2(3) NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'IX_showtime_allocation_status'
      AND object_id = OBJECT_ID(N'dbo.showtime_allocation')
)
BEGIN
    CREATE INDEX IX_showtime_allocation_status
        ON dbo.showtime_allocation(status, branch_id);
    PRINT '  [OK] Created IX_showtime_allocation_status';
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'IX_showtime_allocation_movie'
      AND object_id = OBJECT_ID(N'dbo.showtime_allocation')
)
BEGIN
    CREATE INDEX IX_showtime_allocation_movie
        ON dbo.showtime_allocation(movie_id);
    PRINT '  [OK] Created IX_showtime_allocation_movie';
END
GO

-- ============================================================
-- Bước 2: Bảng showtime_allocation_history
-- Theo dõi lịch sử thay đổi status / quantity (audit trail)
-- ============================================================
IF OBJECT_ID(N'dbo.showtime_allocation_history', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.showtime_allocation_history (
        id              BIGINT IDENTITY(1,1) NOT NULL
                            CONSTRAINT PK_showtime_allocation_history PRIMARY KEY,
        allocation_id   BIGINT NOT NULL
                            CONSTRAINT FK_showtime_allocation_history_alloc
                            REFERENCES dbo.showtime_allocation(id) ON DELETE CASCADE,
        event_type      VARCHAR(30) NOT NULL,
        old_value       NVARCHAR(500) NULL,
        new_value       NVARCHAR(500) NULL,
        actor_user_id   BIGINT NULL
                            CONSTRAINT FK_showtime_allocation_history_actor
                            REFERENCES dbo.user_account(id),
        note            NVARCHAR(500) NULL,
        created_at      DATETIME2(3) NOT NULL
                            CONSTRAINT DF_showtime_allocation_history_created
                            DEFAULT SYSUTCDATETIME(),
        CONSTRAINT CK_showtime_allocation_history_event
            CHECK (event_type IN (
                'ALLOCATED', 'UPDATED', 'PROGRESSED',
                'COMPLETED', 'OVER_ALLOCATED', 'WARNING_SENT'))
    );
    PRINT '  [OK] Created dbo.showtime_allocation_history';
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'IX_showtime_allocation_history_alloc'
      AND object_id = OBJECT_ID(N'dbo.showtime_allocation_history')
)
BEGIN
    CREATE INDEX IX_showtime_allocation_history_alloc
        ON dbo.showtime_allocation_history(allocation_id, created_at DESC);
    PRINT '  [OK] Created IX_showtime_allocation_history_alloc';
END
GO

-- Đồng bộ counter và status từ showtime thật. Script có thể chạy lại an toàn.
;WITH actual_showtimes AS (
    SELECT a.id, COUNT(s.id) AS created_quantity
    FROM dbo.showtime_allocation a
    LEFT JOIN dbo.showtime s
        ON s.movie_id = a.movie_id
       AND s.branch_id = a.branch_id
       AND s.status <> 'CANCELLED'
    GROUP BY a.id
)
UPDATE a
SET created_quantity = x.created_quantity,
    status = CASE
        WHEN x.created_quantity > a.allocated_quantity THEN 'OVER_ALLOCATED'
        WHEN a.allocated_quantity = 0 THEN 'PENDING'
        WHEN x.created_quantity = a.allocated_quantity THEN 'COMPLETED'
        WHEN x.created_quantity = 0 THEN 'PENDING'
        ELSE 'IN_PROGRESS'
    END,
    completed_at = CASE
        WHEN a.allocated_quantity > 0
         AND x.created_quantity = a.allocated_quantity
         AND x.created_quantity > 0
            THEN COALESCE(a.completed_at, SYSUTCDATETIME())
        ELSE NULL
    END,
    updated_at = SYSUTCDATETIME()
FROM dbo.showtime_allocation a
JOIN actual_showtimes x ON x.id = a.id;
GO

PRINT '=== migration_showtime_allocation DONE ===';
GO
