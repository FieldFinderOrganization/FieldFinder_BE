package com.example.FieldFinder.specification;

import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.ProviderAddress;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

public class PitchSpecification {

    public static Specification<Pitch> hasDistrict(String district) {
        return (root, query, criteriaBuilder) -> {
            if (district == null || district.isEmpty()) {
                return null;
            }
            Join<Pitch, ProviderAddress> providerAddressJoin = root.join("providerAddress");
            return criteriaBuilder.like(providerAddressJoin.get("address"), "%" + district + "%");
        };
    }

    public static Specification<Pitch> hasType(String type) {
        return (root, query, criteriaBuilder) -> {
            if (type == null || type.isEmpty()) {
                return null;
            }
            try {
                Pitch.PitchType pitchType = Pitch.PitchType.valueOf(type);
                return criteriaBuilder.equal(root.get("type"), pitchType);
            } catch (IllegalArgumentException e) {
                return null;
            }
        };
    }
}
