package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Trạng thái 1 slot đã chiếm cho màn đặt sân của khách.
 * type: BOOKED (khách khác đã đặt) | MAINTENANCE (sân bảo trì) | OFFLINE (sân bận / đã đặt ngoài app).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SlotStatusDTO {
    private Integer slot;
    private String type;
}
