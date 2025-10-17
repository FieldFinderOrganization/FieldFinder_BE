package com.example.FieldFinder.entity;


import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long categoryId;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @ManyToOne
    @JoinColumn(name = "parent_id")
    private Category parent; // danh mục cha (nếu có)

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    private List<Product> products; // danh sách sản phẩm trong danh mục
}
