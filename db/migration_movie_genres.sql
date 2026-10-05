SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;
GO

IF OBJECT_ID(N'dbo.movie', N'U') IS NULL
    THROW 50001, 'dbo.movie must exist before applying the movie genres migration.', 1;
GO

IF COL_LENGTH(N'dbo.movie', N'genre') IS NULL
    THROW 50002, 'dbo.movie.genre is missing.', 1;
GO

IF COL_LENGTH(N'dbo.movie', N'genre') < 600
    ALTER TABLE dbo.movie ALTER COLUMN genre NVARCHAR(300) NULL;
GO

IF OBJECT_ID(N'dbo.genre', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.genre (
        id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_genre PRIMARY KEY,
        name NVARCHAR(100) NOT NULL CONSTRAINT UQ_genre_name UNIQUE,
        is_active BIT NOT NULL CONSTRAINT DF_genre_is_active DEFAULT 1,
        created_at DATETIME2(3) NOT NULL CONSTRAINT DF_genre_created_at DEFAULT SYSUTCDATETIME()
    );
END
GO

IF OBJECT_ID(N'dbo.movie_genre', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.movie_genre (
        movie_id BIGINT NOT NULL CONSTRAINT FK_movie_genre_movie
            REFERENCES dbo.movie(id) ON DELETE CASCADE,
        genre_id BIGINT NOT NULL CONSTRAINT FK_movie_genre_genre
            REFERENCES dbo.genre(id),
        CONSTRAINT PK_movie_genre PRIMARY KEY(movie_id, genre_id)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.movie_genre')
               AND name = N'IX_movie_genre_genre')
    CREATE INDEX IX_movie_genre_genre ON dbo.movie_genre(genre_id, movie_id);
GO

INSERT INTO dbo.genre(name)
SELECT defaults.name
FROM (VALUES
    (N'Action'), (N'Adventure'), (N'Animation'), (N'Comedy'), (N'Crime'),
    (N'Documentary'), (N'Drama'), (N'Family'), (N'Fantasy'), (N'History'),
    (N'Horror'), (N'Mystery'), (N'Romance'), (N'Sci-Fi'), (N'Thriller'),
    (N'War'), (NCHAR(75) + NCHAR(104) + NCHAR(225) + NCHAR(99))
) defaults(name)
WHERE NOT EXISTS (SELECT 1 FROM dbo.genre g WHERE g.name = defaults.name);
GO

DELETE g
FROM dbo.genre g
WHERE CONVERT(VARBINARY(200), g.name) IN (
    0x4800C300A0006E0068002000C4001820E100BB0022216E006700,
    0x4800C300A0006900,
    0x4B0069006E00680020006400E100BB003920,
    0x500068006900C300AA00750020006C00C600B0007500,
    0x5400C300AC006E00680020006300E100BA00A3006D00,
    0x56006900E100BB0026206E0020007400C600B000E100BB0078016E006700,
    0x4B006800C300A1006300
)
  AND NOT EXISTS (SELECT 1 FROM dbo.movie_genre mg WHERE mg.genre_id = g.id);
GO

INSERT INTO dbo.genre(name)
SELECT DISTINCT LTRIM(RTRIM(parts.value))
FROM dbo.movie m
CROSS APPLY STRING_SPLIT(REPLACE(REPLACE(REPLACE(m.genre, N' / ', N','), N'/', N','), N';', N','), N',') parts
WHERE NULLIF(LTRIM(RTRIM(parts.value)), N'') IS NOT NULL
  AND LEN(LTRIM(RTRIM(parts.value))) <= 100
  AND NOT EXISTS (SELECT 1 FROM dbo.genre g WHERE g.name = LTRIM(RTRIM(parts.value)));
GO

INSERT INTO dbo.movie_genre(movie_id, genre_id)
SELECT DISTINCT m.id, g.id
FROM dbo.movie m
CROSS APPLY STRING_SPLIT(REPLACE(REPLACE(REPLACE(m.genre, N' / ', N','), N'/', N','), N';', N','), N',') parts
JOIN dbo.genre g ON g.name = LTRIM(RTRIM(parts.value)) AND g.is_active = 1
WHERE NULLIF(LTRIM(RTRIM(parts.value)), N'') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM dbo.movie_genre mg
                  WHERE mg.movie_id = m.id AND mg.genre_id = g.id);
GO

UPDATE m
SET genre = labels.genre_names
FROM dbo.movie m
CROSS APPLY (
    SELECT STRING_AGG(g.name, ', ') WITHIN GROUP (ORDER BY g.name) AS genre_names
    FROM dbo.movie_genre mg
    JOIN dbo.genre g ON g.id = mg.genre_id
    WHERE mg.movie_id = m.id
) labels
WHERE labels.genre_names IS NOT NULL;
GO
