package com.cinema.screen;

/** Entity phòng chiếu thuộc một chi nhánh (Req 3). */
public class Screen {
    private Long id;
    private long branchId;
    private String code;
    private String name;
    private int rowCount;
    private int colCount;
    private String status;
    private int version;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public long branchId() { return branchId; }
    public void setBranchId(long branchId) { this.branchId = branchId; }
    public String code() { return code; }
    public void setCode(String code) { this.code = code; }
    public String name() { return name; }
    public void setName(String name) { this.name = name; }
    public int rowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public int colCount() { return colCount; }
    public void setColCount(int colCount) { this.colCount = colCount; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int version() { return version; }
    public void setVersion(int version) { this.version = version; }
}
