-- ============================================================
-- Migration: Thêm cột author (tác giả) cho bảng dbo.movie
-- Tác giả là thông tin metadata bổ sung cho phim (đạo diễn / tác giả kịch bản)
-- Idempotent: chỉ thêm nếu cột chưa tồn tại
-- Chạy: sqlcmd -S "localhost\SQLEXPRESS" -U sa -P long -C -d CinemaManagement -i db/migration_movie_author.sql
-- ============================================================

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

USE [CinemaManagement];
GO

IF COL_LENGTH(N'dbo.movie', N'author') IS NULL
BEGIN
    ALTER TABLE dbo.movie ADD author NVARCHAR(200) NULL;
    PRINT 'Added column dbo.movie.author';
END
ELSE
BEGIN
    PRINT 'Column dbo.movie.author already exists - skipped';
END
GO
