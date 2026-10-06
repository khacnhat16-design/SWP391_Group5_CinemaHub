package com.cinema.screen;

/** Entity ghế thuộc sơ đồ ghế của một phòng (Req 3). */
public class Seat {
    private Long id;
    private long screenId;
    private String rowLabel;
    private int colNo;
    private String seatType;
    private String status;

    public static final String TYPE_STANDARD = "STANDARD";
    public static final String TYPE_VIP = "VIP";
    public static final String TYPE_COUPLE = "COUPLE";

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public long screenId() { return screenId; }
    public void setScreenId(long screenId) { this.screenId = screenId; }
    public String rowLabel() { return rowLabel; }
    public void setRowLabel(String rowLabel) { this.rowLabel = rowLabel; }
    public int colNo() { return colNo; }
    public void setColNo(int colNo) { this.colNo = colNo; }
    public String seatType() { return seatType; }
    public void setSeatType(String seatType) { this.seatType = seatType; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
}
