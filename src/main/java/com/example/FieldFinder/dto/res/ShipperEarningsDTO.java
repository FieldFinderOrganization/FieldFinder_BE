package com.example.FieldFinder.dto.res;

import lombok.Builder;
import lombok.Data;

/**
 * Thu nhập shipper tính server-side = tổng phí ship GỐC (grossShippingFee, fallback shippingFee
 * cho đơn cũ) trên các đơn DELIVERED, chia theo kỳ. Khách được freeship vẫn tính cho shipper.
 */
@Data
@Builder
public class ShipperEarningsDTO {
    private double today;
    private double week;   // từ thứ 2 đầu tuần tới nay
    private double month;  // trong tháng hiện tại
    private int todayCount;
    private int weekCount;
    private int monthCount;
}
