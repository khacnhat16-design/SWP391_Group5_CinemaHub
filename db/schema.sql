-- Cinema Management SQL Server schema
-- Run: sqlcmd -S "DESKTOP-J0616K5\SQLEXPRESS,1433" -U sa -P long -C -v CinemaDbName=CinemaManagement -i db/schema.sql
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;
GO
IF DB_ID(N'CinemaManagement') IS NULL
BEGIN
    DECLARE @createSql NVARCHAR(300) = N'CREATE DATABASE ' + QUOTENAME(N'CinemaManagement');
    EXEC sp_executesql @createSql;
END
GO
USE [CinemaManagement];
GO

-- ============ Identity & access ============
IF OBJECT_ID(N'dbo.role', N'U') IS NULL
CREATE TABLE dbo.role (
    code VARCHAR(30) NOT NULL CONSTRAINT PK_role PRIMARY KEY,
    display_name NVARCHAR(50) NOT NULL CONSTRAINT UQ_role_display_name UNIQUE,
);
GO
IF OBJECT_ID(N'dbo.branch', N'U') IS NULL
CREATE TABLE dbo.branch (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_branch PRIMARY KEY,
    name NVARCHAR(100) NOT NULL CONSTRAINT UQ_branch_name UNIQUE,
    address NVARCHAR(255) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT DF_branch_status DEFAULT 'ACTIVE',
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_branch_created DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_branch_updated DEFAULT SYSUTCDATETIME(),
    version INT NOT NULL CONSTRAINT DF_branch_version DEFAULT 0,
);
GO
IF OBJECT_ID(N'dbo.user_account', N'U') IS NULL
CREATE TABLE dbo.user_account (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_user_account PRIMARY KEY,
    email NVARCHAR(100) NOT NULL CONSTRAINT UQ_user_email UNIQUE,
    phone VARCHAR(20) NOT NULL CONSTRAINT UQ_user_phone UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name NVARCHAR(100) NOT NULL,
    role_code VARCHAR(30) NOT NULL CONSTRAINT FK_user_role REFERENCES dbo.role(code),
    status VARCHAR(20) NOT NULL CONSTRAINT DF_user_status DEFAULT 'ACTIVE',
    failed_login_count INT NOT NULL CONSTRAINT DF_user_failures DEFAULT 0,
    locked_until DATETIME2(3) NULL,
    last_login_at DATETIME2(3) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_user_created DEFAULT SYSUTCDATETIME(),
    version INT NOT NULL CONSTRAINT DF_user_version DEFAULT 0,
    email_verified BIT NOT NULL CONSTRAINT DF_user_email_verified DEFAULT 1,
    email_verification_token VARCHAR(128) NULL,
    email_verification_expires_at DATETIME2(3) NULL,
);
GO
IF COL_LENGTH(N'dbo.user_account', N'email_verified') IS NULL
BEGIN
    ALTER TABLE dbo.user_account ADD email_verified BIT NOT NULL
        CONSTRAINT DF_user_email_verified_migration DEFAULT 1;
END
GO
IF COL_LENGTH(N'dbo.user_account', N'email_verification_token') IS NULL
    ALTER TABLE dbo.user_account ADD email_verification_token VARCHAR(128) NULL;
GO
IF COL_LENGTH(N'dbo.user_account', N'email_verification_expires_at') IS NULL
    ALTER TABLE dbo.user_account ADD email_verification_expires_at DATETIME2(3) NULL;
GO
IF OBJECT_ID(N'dbo.customer_profile', N'U') IS NULL
CREATE TABLE dbo.customer_profile (
    user_id BIGINT NOT NULL CONSTRAINT PK_customer_profile PRIMARY KEY CONSTRAINT FK_customer_user REFERENCES dbo.user_account(id),
    points INT NOT NULL CONSTRAINT DF_customer_points DEFAULT 0,
    tier NVARCHAR(50) NOT NULL CONSTRAINT DF_customer_tier DEFAULT N'STANDARD'
);
GO
IF OBJECT_ID(N'dbo.staff_branch_assignment', N'U') IS NULL
CREATE TABLE dbo.staff_branch_assignment (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_staff_assignment PRIMARY KEY,
    user_id BIGINT NOT NULL CONSTRAINT FK_assignment_user REFERENCES dbo.user_account(id),
    branch_id BIGINT NOT NULL CONSTRAINT FK_assignment_branch REFERENCES dbo.branch(id),
    effective_from DATETIME2(3) NOT NULL CONSTRAINT DF_assignment_from DEFAULT SYSUTCDATETIME(),
    effective_to DATETIME2(3) NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT DF_assignment_status DEFAULT 'ACTIVE',
    assigned_by BIGINT NOT NULL CONSTRAINT FK_assignment_admin REFERENCES dbo.user_account(id),
);
Go

-- ============ Catalog & scheduling ============
IF OBJECT_ID(N'dbo.movie', N'U') IS NULL
CREATE TABLE dbo.movie (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_movie PRIMARY KEY,
    title NVARCHAR(200) NOT NULL,
    duration_min INT NOT NULL,
    genre NVARCHAR(100) NULL,
    rating VARCHAR(10) NOT NULL,
    release_date DATE NOT NULL,
    end_date DATE NOT NULL,
    poster_url NVARCHAR(500) NULL,
    description NVARCHAR(MAX) NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT DF_movie_status DEFAULT 'DRAFT',
    version INT NOT NULL CONSTRAINT DF_movie_version DEFAULT 0,
    
);

