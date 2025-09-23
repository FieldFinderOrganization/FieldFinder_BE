package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.ai.AIChat;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AIResponse {
    @Setter
    private String message;
    private AIChat.BookingQuery bookingQuery;

    public String getMessage() {
        return message;
    }

    public AIChat.BookingQuery getBookingQuery() {
        return bookingQuery;
    }

    public void setBookingQuery(AIChat.BookingQuery bookingQuery) {
        this.bookingQuery = bookingQuery;
    }
}
