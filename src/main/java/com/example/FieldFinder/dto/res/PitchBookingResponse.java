package com.example.FieldFinder.dto.res;

import java.util.List;

public class PitchBookingResponse {
    private String pitchId;
    private String bookingDate;
    private List<Integer> slotList;

    public PitchBookingResponse() {}

    public PitchBookingResponse(String pitchId, String bookingDate, List<Integer> slotList) {
        this.pitchId = pitchId;
        this.bookingDate = bookingDate;
        this.slotList = slotList;
    }

    public String getPitchId() {
        return pitchId;
    }

    public void setPitchId(String pitchId) {
        this.pitchId = pitchId;
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
}
