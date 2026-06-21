package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "providers")
@Builder
public class Provider {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "provider_id")
    private UUID providerId;

    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    // TK ngân hàng nhận tiền của chủ sân lưu ở entity BankAccount (key theo userId) — nguồn DUY NHẤT,
    // dùng cho cả hiển thị lúc thu tiền lẫn payout. Bỏ card_number/bank cũ (text rời, trùng lặp).

    @OneToMany(mappedBy = "provider", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProviderAddress> addresses = new ArrayList<>();
}