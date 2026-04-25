package com.example.FieldFinder.dto.req;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AssignDiscountRequestDTO {
    private List<UUID> userIds;
}
