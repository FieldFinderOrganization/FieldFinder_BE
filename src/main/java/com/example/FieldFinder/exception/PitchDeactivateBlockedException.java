package com.example.FieldFinder.exception;

import java.time.LocalDate;

/**
 * Ném ra khi không thể ngưng sân vì còn booking CONFIRMED trong tương lai.
 */
public class PitchDeactivateBlockedException extends RuntimeException {

    private final int confirmedBookingCount;
    private final LocalDate earliestDeactivationDate;

    public PitchDeactivateBlockedException(int confirmedBookingCount, LocalDate earliestDeactivationDate) {
        super("Không thể ngưng sân vì còn " + confirmedBookingCount + " lịch CONFIRMED. "
                + "Có thể ngưng sớm nhất từ ngày " + earliestDeactivationDate);
        this.confirmedBookingCount = confirmedBookingCount;
        this.earliestDeactivationDate = earliestDeactivationDate;
    }

    public int getConfirmedBookingCount() {
        return confirmedBookingCount;
    }

    public LocalDate getEarliestDeactivationDate() {
        return earliestDeactivationDate;
    }
}
