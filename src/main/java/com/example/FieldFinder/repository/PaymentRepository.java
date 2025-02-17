package com.example.FieldFinder.repository;

package com.pitchbooking.application.repository;

import com.pitchbooking.application.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
}
