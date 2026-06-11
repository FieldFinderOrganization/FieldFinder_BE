package com.example.FieldFinder.entity;


import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "order_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderItemId;

    // Quan hệ JPA phải loại khỏi equals/hashCode/toString: Hibernate flush (PersistentBag
    // equalsSnapshot) hash từng OrderItem -> hashCode lan sang order/product/category.parent
    // (proxy LAZY detached) -> nổ LazyInitializationException khi đặt hàng (POST /api/orders 400).
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "size", nullable = true)
    private String size;

    private Integer quantity;
    private Double price;
}
