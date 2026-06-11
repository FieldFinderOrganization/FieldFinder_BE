package com.example.FieldFinder.entity;


import jakarta.persistence.*;
import lombok.*;
import com.example.FieldFinder.Enum.CategoryType;
import java.util.List;

@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@org.hibernate.annotations.BatchSize(size = 50)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long categoryId;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CategoryType categoryType = CategoryType.STANDARD;

    // parent là proxy LAZY: gọi hashCode() trên proxy detached -> LazyInitializationException.
    // Loại mọi quan hệ khỏi equals/hashCode/toString (xem chú thích ở OrderItem).
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    private List<Category> children;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    private List<Product> products;
}
