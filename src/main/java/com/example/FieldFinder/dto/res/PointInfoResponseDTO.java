package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.PointTransaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointInfoResponseDTO {

    private int balance;
    private List<PointTransactionDTO> transactions;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PointTransactionDTO {
        private int amount;       // signed
        private String type;      // EARN_ORDER | REVERT_ORDER | REDEEM_VOUCHER
        private String description;
        private LocalDateTime createdAt;

        public static PointTransactionDTO fromEntity(PointTransaction tx) {
            return PointTransactionDTO.builder()
                    .amount(tx.getAmount())
                    .type(tx.getType().name())
                    .description(tx.getDescription())
                    .createdAt(tx.getCreatedAt())
                    .build();
        }
    }
}
