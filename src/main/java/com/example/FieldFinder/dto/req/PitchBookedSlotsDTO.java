package com.example.FieldFinder.dto.req;

import java.util.List;
import java.util.UUID;

public class PitchBookedSlotsDTO {
    private UUID pitchId;
    private List<Integer> bookedSlots;

    // Constructors
    public PitchBookedSlotsDTO() {}

    public PitchBookedSlotsDTO(UUID pitchId, List<Integer> bookedSlots) {
        this.pitchId = pitchId;
        this.bookedSlots = bookedSlots;
    }

    // Getters and Setters
    public UUID getPitchId() {
        return pitchId;
    }

    public void setPitchId(UUID pitchId) {
        this.pitchId = pitchId;
    }

    public List<Integer> getBookedSlots() {
        return bookedSlots;
    }

    public void setBookedSlots(List<Integer> bookedSlots) {
        this.bookedSlots = bookedSlots;
    }
}
