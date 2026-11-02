-- =====================================================================
-- Inventory 2.0: Raw material + recipe + production
-- Bổ sung: material, branch_material_inventory, material_adjustment, recipe_item
-- Áp dụng cho cả SQL Server (production) — tương thích cú pháp IF OBJECT_ID.
-- =====================================================================
USE [$(CinemaDbName)];
GO

-- ============ MATERIAL (nguyên liệu thô) ============
IF OBJECT_ID(N'dbo.material', N'U') IS NULL
CREATE TABLE dbo.material (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_material PRIMARY KEY,
    code VARCHAR(40) NOT NULL CONSTRAINT UQ_material_code UNIQUE,
    name NVARCHAR(150) NOT NULL,
    category VARCHAR(40) NOT NULL CONSTRAINT DF_material_category DEFAULT 'OTHER',
    unit NVARCHAR(20) NOT NULL,
    min_stock DECIMAL(18,3) NOT NULL CONSTRAINT DF_material_min DEFAULT 0,
    cost_price BIGINT NOT NULL CONSTRAINT DF_material_cost DEFAULT 0,
    supplier NVARCHAR(150) NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT DF_material_status DEFAULT 'ACTIVE',
    note NVARCHAR(500) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_material_created DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_material_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_material_status CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT CK_material_category CHECK (category IN ('POPCORN','DRINK','COMBO','PACKAGING','INGREDIENT','OTHER')),
    CONSTRAINT CK_material_min_stock CHECK (min_stock >= 0),
    CONSTRAINT CK_material_cost_price CHECK (cost_price >= 0)
);
GO

-- ============ BRANCH MATERIAL INVENTORY (tồn kho NL theo chi nhánh) ============
IF OBJECT_ID(N'dbo.branch_material_inventory', N'U') IS NULL
CREATE TABLE dbo.branch_material_inventory (
    branch_id BIGINT NOT NULL CONSTRAINT FK_bmi_branch REFERENCES dbo.branch(id),
    material_id BIGINT NOT NULL CONSTRAINT FK_bmi_material REFERENCES dbo.material(id),
    qty DECIMAL(18,3) NOT NULL CONSTRAINT DF_bmi_qty DEFAULT 0,
    updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_bmi_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_branch_material_inventory PRIMARY KEY(branch_id, material_id),
    CONSTRAINT CK_bmi_qty CHECK (qty >= 0)
);
GO

-- ============ MATERIAL ADJUSTMENT (lịch sử biến động NL) ============
IF OBJECT_ID(N'dbo.material_adjustment', N'U') IS NULL
CREATE TABLE dbo.material_adjustment (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_material_adjustment PRIMARY KEY,
    branch_id BIGINT NOT NULL CONSTRAINT FK_mat_adj_branch REFERENCES dbo.branch(id),
    material_id BIGINT NOT NULL CONSTRAINT FK_mat_adj_material REFERENCES dbo.material(id),
    delta_qty DECIMAL(18,3) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    cost_price BIGINT NULL,
    supplier NVARCHAR(150) NULL,
    po_number VARCHAR(50) NULL,
    note NVARCHAR(500) NULL,
    actor_id BIGINT NOT NULL CONSTRAINT FK_mat_adj_actor REFERENCES dbo.user_account(id),
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_mat_adj_created DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_mat_adj_delta CHECK (delta_qty <> 0)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.material_adjustment') AND name = N'IX_mat_adj_branch_material')
CREATE INDEX IX_mat_adj_branch_material ON dbo.material_adjustment(branch_id, material_id, created_at DESC);
GO

-- ============ RECIPE ITEM (công thức chế biến định mức) ============
IF OBJECT_ID(N'dbo.recipe_item', N'U') IS NULL
CREATE TABLE dbo.recipe_item (
    id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT PK_recipe_item PRIMARY KEY,
    product_id BIGINT NOT NULL CONSTRAINT FK_recipe_product REFERENCES dbo.product(id),
    material_id BIGINT NOT NULL CONSTRAINT FK_recipe_material REFERENCES dbo.material(id),
    -- Định mức nguyên liệu cho 1 đơn vị thành phẩm
    dosage DECIMAL(18,3) NOT NULL,
    quantity_required DECIMAL(18,3) NULL, -- tương đương dosage (giữ để tương thích DAO cũ)
    unit NVARCHAR(20) NULL,
    loss_rate DECIMAL(8,4) NOT NULL CONSTRAINT DF_recipe_loss DEFAULT 0,
    note NVARCHAR(500) NULL,
    is_active BIT NOT NULL CONSTRAINT DF_recipe_active DEFAULT 1,
    created_at DATETIME2(3) NOT NULL CONSTRAINT DF_recipe_created DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_recipe_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_recipe_dosage CHECK (dosage > 0),
    CONSTRAINT CK_recipe_loss_rate CHECK (loss_rate >= 0 AND loss_rate < 1),
    CONSTRAINT UQ_recipe_product_material UNIQUE(product_id, material_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.recipe_item') AND name = N'IX_recipe_product_active')
CREATE INDEX IX_recipe_product_active ON dbo.recipe_item(product_id, is_active);
GO

-- ============ SEED: một số nguyên liệu mẫu (idempotent) ============
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-POPCORN-CORN')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-POPCORN-CORN', N'Hạt bắp rang', 'POPCORN', N'kg', 5.000, 45000, N'Supplier A');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-POPCORN-OIL')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-POPCORN-OIL', N'Dầu bắp rang', 'POPCORN', N'lit', 2.000, 80000, N'Supplier A');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-CUP-L')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-CUP-L', N'Ly lớn 32oz', 'PACKAGING', N'cái', 200.000, 2500, N'Supplier B');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-CUP-M')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-CUP-M', N'Ly vừa 22oz', 'PACKAGING', N'cái', 200.000, 1800, N'Supplier B');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-CUP-S')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-CUP-S', N'Ly nhỏ 12oz', 'PACKAGING', N'cái', 200.000, 1200, N'Supplier B');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-SODA')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-SODA', N'Syrup soda', 'DRINK', N'lit', 10.000, 35000, N'Supplier C');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-WATER')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-WATER', N'Nước tinh khiết', 'DRINK', N'lit', 50.000, 4000, N'Supplier C');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.material WHERE code = 'MAT-ICE')
INSERT INTO dbo.material (code, name, category, unit, min_stock, cost_price, supplier)
VALUES ('MAT-ICE', N'Đá viên', 'INGREDIENT', N'kg', 30.000, 3000, N'Supplier C');
GO
