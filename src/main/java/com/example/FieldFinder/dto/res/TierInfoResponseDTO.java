package com.example.FieldFinder.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TierInfoResponseDTO {
    private String tier;
    private double totalSpent12m;
    private String nextTier;          // null nếu đã DIAMOND
    private Long nextTierThreshold;   // null nếu đã DIAMOND
    private Double amountToNextTier;  // null nếu đã DIAMOND
    private int progressPercent;      // 0-100 tiến độ tới hạng kế (DIAMOND = 100)
}
