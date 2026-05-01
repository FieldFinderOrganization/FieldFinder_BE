package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.DiscountRequestDTO;
import com.example.FieldFinder.dto.req.UserDiscountRequestDTO;
import com.example.FieldFinder.dto.res.DiscountResponseDTO;
import com.example.FieldFinder.dto.res.UserDiscountResponseDTO;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.DiscountRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscountServiceImplTest {

    @Mock DiscountRepository discountRepository;
    @Mock UserDiscountRepository userDiscountRepository;
    @Mock UserRepository userRepository;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @InjectMocks DiscountServiceImpl service;

    private Discount discount;
    private UUID discountId;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        discountId = UUID.randomUUID();
        userId = UUID.randomUUID();

        discount = Discount.builder()
                .discountId(discountId)
                .code("SALE10")
                .description("desc")
                .discountType(Discount.DiscountType.PERCENTAGE)
                .value(BigDecimal.TEN)
                .scope(Discount.DiscountScope.GLOBAL)
                .quantity(5)
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(10))
                .status(Discount.DiscountStatus.ACTIVE)
                .applicableProducts(new HashSet<>())
                .applicableCategories(new HashSet<>())
                .build();

        user = new User();
        user.setUserId(userId);

        // Stub redis keys mặc định trả null để clearProductCacheByDiscount no-op
        lenient().when(redisTemplate.keys(anyString())).thenReturn(null);
    }

    private DiscountRequestDTO buildRequest() {
        DiscountRequestDTO dto = new DiscountRequestDTO();
        dto.setCode("SALE10");
        dto.setDescription("desc");
        dto.setDiscountType("PERCENTAGE");
        dto.setValue(BigDecimal.TEN);
        dto.setScope("GLOBAL");
        dto.setQuantity(5);
        dto.setStartDate(LocalDate.now().minusDays(1));
        dto.setEndDate(LocalDate.now().plusDays(10));
        dto.setStatus("ACTIVE");
        return dto;
    }

    @Nested
    class createDiscount {
        @Test
        void newCode_savesAndAssignsToAllUsers() {
            when(discountRepository.existsByCode("SALE10")).thenReturn(false);
            when(discountRepository.save(any(Discount.class))).thenReturn(discount);

            DiscountResponseDTO result = service.createDiscount(buildRequest());

            assertNotNull(result);
            verify(discountRepository, times(1)).save(any(Discount.class));
            verify(userDiscountRepository, times(1))
                    .bulkAssignToAllUsers(discountId);
        }

        @Test
        void duplicateCode_ThrowsException() {
            when(discountRepository.existsByCode("SALE10")).thenReturn(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createDiscount(buildRequest()));
            assertTrue(ex.getMessage().contains("already exists"));

            verify(discountRepository, never()).save(any(Discount.class));
            verify(userDiscountRepository, never()).bulkAssignToAllUsers(any());
        }
    }

    @Nested
    class updateDiscount {
        @Test
        void existing_updatesFieldsAndSaves() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.of(discount));
            when(discountRepository.save(any(Discount.class))).thenAnswer(inv -> inv.getArgument(0));

            DiscountRequestDTO req = buildRequest();
            req.setCode("SALE20");
            req.setValue(BigDecimal.valueOf(20));
            req.setStatus("INACTIVE");

            DiscountResponseDTO result = service.updateDiscount(discountId.toString(), req);

            assertNotNull(result);
            assertEquals("SALE20", discount.getCode());
            assertEquals(BigDecimal.valueOf(20), discount.getValue());
            assertEquals(Discount.DiscountStatus.INACTIVE, discount.getStatus());
            verify(discountRepository).save(discount);
        }

        @Test
        void notFound_ThrowsException() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updateDiscount(discountId.toString(), buildRequest()));
            assertTrue(ex.getMessage().contains("Discount not found"));
        }

        @Test
        void invalidStatus_FallsBackToInactive() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.of(discount));
            when(discountRepository.save(any(Discount.class))).thenAnswer(inv -> inv.getArgument(0));

            DiscountRequestDTO req = buildRequest();
            req.setStatus("WHATEVER");

            service.updateDiscount(discountId.toString(), req);

            assertEquals(Discount.DiscountStatus.INACTIVE, discount.getStatus());
        }
    }

    @Nested
    class deleteDiscount {
        @Test
        void existing_deletes() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.of(discount));

            service.deleteDiscount(discountId.toString());

            verify(discountRepository, times(1)).delete(discount);
        }

        @Test
        void notFound_NoOp() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.empty());

            service.deleteDiscount(discountId.toString());

            verify(discountRepository, never()).delete(any(Discount.class));
        }
    }

    @Nested
    class getAllDiscounts {
        @Test
        void hasData_ReturnsList() {
            when(discountRepository.findAll()).thenReturn(List.of(discount));

            List<DiscountResponseDTO> result = service.getAllDiscounts();

            assertEquals(1, result.size());
            verify(discountRepository, times(1)).findAll();
        }

        @Test
        void empty_ReturnsEmpty() {
            when(discountRepository.findAll()).thenReturn(Collections.emptyList());

            assertTrue(service.getAllDiscounts().isEmpty());
        }
    }

    @Nested
    class getDiscountById {
        @Test
        void hasData_ReturnsResponseDTO() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.of(discount));

            DiscountResponseDTO result = service.getDiscountById(discountId.toString());

            assertNotNull(result);
            assertEquals("SALE10", result.getCode());
        }

        @Test
        void notFound_ThrowsException() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getDiscountById(discountId.toString()));
            assertTrue(ex.getMessage().contains("Discount not found"));
        }
    }

    @Nested
    class saveDiscountToWallet {

        private UserDiscountRequestDTO req;

        @BeforeEach
        void setUpReq() {
            req = new UserDiscountRequestDTO();
            req.setDiscountCode("SALE10");
        }

        @Test
        void valid_savesAndDecrementsQuantity() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(discountRepository.findByCode("SALE10")).thenReturn(Optional.of(discount));
            when(userDiscountRepository.existsByUserAndDiscount(user, discount)).thenReturn(false);

            int before = discount.getQuantity();
            service.saveDiscountToWallet(userId, req);

            verify(userDiscountRepository, times(1)).save(any(UserDiscount.class));
            verify(discountRepository, times(1)).save(discount);
            assertEquals(before - 1, discount.getQuantity());
        }

        @Test
        void userNotFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.saveDiscountToWallet(userId, req));
            assertTrue(ex.getMessage().contains("User not found"));
        }

        @Test
        void invalidCode_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(discountRepository.findByCode("SALE10")).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.saveDiscountToWallet(userId, req));
            assertTrue(ex.getMessage().contains("Discount code invalid"));
        }

        @Test
        void inactiveStatus_ThrowsException() {
            discount.setStatus(Discount.DiscountStatus.INACTIVE);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(discountRepository.findByCode("SALE10")).thenReturn(Optional.of(discount));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.saveDiscountToWallet(userId, req));
            assertTrue(ex.getMessage().contains("not active"));
        }

        @Test
        void expired_ThrowsException() {
            discount.setEndDate(LocalDate.now().minusDays(1));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(discountRepository.findByCode("SALE10")).thenReturn(Optional.of(discount));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.saveDiscountToWallet(userId, req));
            assertTrue(ex.getMessage().contains("expired"));
        }

        @Test
        void outOfStock_ThrowsException() {
            discount.setQuantity(0);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(discountRepository.findByCode("SALE10")).thenReturn(Optional.of(discount));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.saveDiscountToWallet(userId, req));
            assertTrue(ex.getMessage().contains("out of stock"));
        }

        @Test
        void alreadySaved_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(discountRepository.findByCode("SALE10")).thenReturn(Optional.of(discount));
            when(userDiscountRepository.existsByUserAndDiscount(user, discount)).thenReturn(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.saveDiscountToWallet(userId, req));
            assertTrue(ex.getMessage().contains("already saved"));

            verify(userDiscountRepository, never()).save(any(UserDiscount.class));
        }
    }

    @Nested
    class getMyWallet {
        @Test
        void hasData_ReturnsList() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            UserDiscount ud = UserDiscount.builder()
                    .id(UUID.randomUUID())
                    .user(user).discount(discount)
                    .isUsed(false).build();
            when(userDiscountRepository.findWalletByUserId(userId)).thenReturn(List.of(ud));

            List<UserDiscountResponseDTO> result = service.getMyWallet(userId);

            assertEquals(1, result.size());
            assertEquals("SALE10", result.getFirst().getDiscountCode());
        }

        @Test
        void userNotFound_ThrowsException() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getMyWallet(userId));
            assertTrue(ex.getMessage().contains("User not found"));
        }
    }

    @Nested
    class updateStatus {
        @Test
        void existing_updatesStatus() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.of(discount));
            when(discountRepository.save(any(Discount.class))).thenAnswer(inv -> inv.getArgument(0));

            DiscountResponseDTO result = service.updateStatus(
                    discountId.toString(), Discount.DiscountStatus.EXPIRED);

            assertEquals(Discount.DiscountStatus.EXPIRED, discount.getStatus());
            assertNotNull(result);
        }

        @Test
        void notFound_ThrowsException() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updateStatus(discountId.toString(), Discount.DiscountStatus.EXPIRED));
            assertTrue(ex.getMessage().contains("Discount not found"));
        }
    }

    @Nested
    class assignToUsers {
        @Test
        void validUsers_savesNewAssignmentsAndSkipsDuplicates() {
            UUID otherUserId = UUID.randomUUID();
            User other = new User();
            other.setUserId(otherUserId);

            when(discountRepository.findById(discountId)).thenReturn(Optional.of(discount));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.findById(otherUserId)).thenReturn(Optional.of(other));
            when(userDiscountRepository.existsByUserAndDiscount(user, discount)).thenReturn(true); // duplicate
            when(userDiscountRepository.existsByUserAndDiscount(other, discount)).thenReturn(false);

            service.assignToUsers(discountId.toString(), List.of(userId, otherUserId));

            verify(userDiscountRepository, times(1)).save(any(UserDiscount.class));
        }

        @Test
        void unknownUserId_skipped() {
            UUID missingId = UUID.randomUUID();
            when(discountRepository.findById(discountId)).thenReturn(Optional.of(discount));
            when(userRepository.findById(missingId)).thenReturn(Optional.empty());

            service.assignToUsers(discountId.toString(), List.of(missingId));

            verify(userDiscountRepository, never()).save(any(UserDiscount.class));
        }

        @Test
        void notFoundDiscount_ThrowsException() {
            when(discountRepository.findById(discountId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.assignToUsers(discountId.toString(), List.of(userId)));
            assertTrue(ex.getMessage().contains("Discount not found"));
        }
    }
}