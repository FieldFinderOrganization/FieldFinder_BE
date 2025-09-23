package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.Pitch;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class PitchBookingResponse {
    private UUID pitchId;
    private String name;
    private BigDecimal price;
    private String description;
    private String bookingDate;
    private List<Integer> slotList;
    private String pitchType;

    // Constructor mặc định
    public PitchBookingResponse() {}

    // Constructor với tất cả tham số kiểu String
    public PitchBookingResponse(UUID pitchId, String name, BigDecimal price, String description,
                                String bookingDate, List<Integer> slotList, String pitchType) {
        this.pitchId = pitchId;
        this.name = name;
        this.price = price;
        this.description = description;
        this.bookingDate = bookingDate;
        this.slotList = slotList;
        this.pitchType = pitchType;
    }

    // Constructor với Pitch.PitchType, chuyển đổi enum thành String
    public PitchBookingResponse(UUID pitchId, String name, BigDecimal price, String description,
                                String bookingDate, List<Integer> slotList, Pitch.PitchType type) {
        this.pitchId = pitchId;
        this.name = name;
        this.price = price;
        this.description = description;
        this.bookingDate = bookingDate;
        this.slotList = slotList;
        this.pitchType = type != null ? type.name() : null; // Chuyển đổi PitchType thành String
    }

    // Getters và Setters
    public UUID getPitchId() {
        return pitchId;
    }

    public void setPitchId(UUID pitchId) {
        this.pitchId = pitchId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBookingDate() {
        return bookingDate;
    }

    public void setBookingDate(String bookingDate) {
        this.bookingDate = bookingDate;
    }

    public List<Integer> getSlotList() {
        return slotList;
    }

    public void setSlotList(List<Integer> slotList) {
        this.slotList = slotList;
    }

    // Phương thức getPitchType đã có sẵn
    public String getPitchType() {
        return pitchType;
    }

    public void setPitchType(String pitchType) {
        this.pitchType = pitchType;
    }
}