package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.PitchEnvironment;
import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PitchServiceImplTest {

    @Mock PitchRepository pitchRepository;
    @Mock ProviderAddressRepository providerAddressRepository;
    @Mock BookingDetailRepository bookingDetailRepository;

    @InjectMocks PitchServiceImpl service;

    private UUID pitchId;
    private UUID providerAddressId;
    private Pitch pitch;
    private ProviderAddress providerAddress;

    @BeforeEach
    void setUp() {
        pitchId = UUID.randomUUID();
        providerAddressId = UUID.randomUUID();

        Provider provider = new Provider();
        User providerUser = new User();
        providerUser.setUserId(UUID.randomUUID());
        providerUser.setName("Owner");
        provider.setUser(providerUser);

        providerAddress = new ProviderAddress();
        providerAddress.setProviderAddressId(providerAddressId);
        providerAddress.setProvider(provider);

        pitch = Pitch.builder()
                .pitchId(pitchId)
                .providerAddress(providerAddress)
                .name("Sân số 1")
                .type(Pitch.PitchType.FIVE_A_SIDE)
                .environment(PitchEnvironment.OUTDOOR)
                .price(new BigDecimal("200000"))
                .description("Sân cỏ nhân tạo")
                .build();
    }

    private PitchRequestDTO buildRequest() {
        PitchRequestDTO dto = new PitchRequestDTO();
        dto.setProviderAddressId(providerAddressId);
        dto.setName("Sân số 1");
        dto.setType(Pitch.PitchType.FIVE_A_SIDE);
        dto.setEnvironment(PitchEnvironment.OUTDOOR);
        dto.setPrice(new BigDecimal("200000"));
        dto.setDescription("Sân cỏ nhân tạo");
        dto.setImageUrls(List.of("a.jpg"));
        return dto;
    }

    @Nested
    class createPitch {
        @Test
        void valid_returnsResponseDTO() {
            when(providerAddressRepository.findById(providerAddressId))
                    .thenReturn(Optional.of(providerAddress));
            when(pitchRepository.save(any(Pitch.class))).thenReturn(pitch);

            PitchResponseDTO result = service.createPitch(buildRequest());

            assertNotNull(result);
            assertEquals("Sân số 1", result.getName());
            verify(pitchRepository, times(1)).save(any(Pitch.class));
        }

        @Test
        void providerAddressNotFound_ThrowsException() {
            when(providerAddressRepository.findById(providerAddressId))
                    .thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createPitch(buildRequest()));
            assertTrue(ex.getMessage().contains("ProviderAddress not found"));
            verify(pitchRepository, never()).save(any());
        }
    }

    @Nested
    class updatePitch {
        @Test
        void existing_updatesAndSaves() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.of(pitch));
            when(pitchRepository.save(any(Pitch.class))).thenAnswer(inv -> inv.getArgument(0));

            PitchRequestDTO req = buildRequest();
            req.setName("Sân số 2");
            req.setPrice(new BigDecimal("300000"));

            PitchResponseDTO result = service.updatePitch(pitchId, req);

            assertNotNull(result);
            assertEquals("Sân số 2", pitch.getName());
            assertEquals(new BigDecimal("300000"), pitch.getPrice());
            verify(pitchRepository).save(pitch);
        }

        @Test
        void notFound_ThrowsException() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updatePitch(pitchId, buildRequest()));
            assertTrue(ex.getMessage().contains("Pitch not found"));
        }
    }

    @Nested
    class getPitchesByProviderAddressId {
        @Test
        void hasData_ReturnsList() {
            when(pitchRepository.findByProviderAddressProviderAddressId(providerAddressId))
                    .thenReturn(List.of(pitch));

            List<PitchResponseDTO> result = service.getPitchesByProviderAddressId(providerAddressId);

            assertEquals(1, result.size());
            assertEquals("Sân số 1", result.getFirst().getName());
        }

        @Test
        void empty_ReturnsEmpty() {
            when(pitchRepository.findByProviderAddressProviderAddressId(providerAddressId))
                    .thenReturn(List.of());

            assertTrue(service.getPitchesByProviderAddressId(providerAddressId).isEmpty());
        }
    }

    @Nested
    class deletePitch {
        @Test
        void noBookings_deletes() {
            when(pitchRepository.existsById(pitchId)).thenReturn(true);
            when(bookingDetailRepository.existsByPitch_PitchId(pitchId)).thenReturn(false);

            service.deletePitch(pitchId);

            verify(pitchRepository, times(1)).deleteById(pitchId);
        }

        @Test
        void notFound_ThrowsException() {
            when(pitchRepository.existsById(pitchId)).thenReturn(false);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.deletePitch(pitchId));
            assertTrue(ex.getMessage().contains("Pitch not found"));
            verify(pitchRepository, never()).deleteById(any());
        }

        @Test
        void hasRelatedBookings_ThrowsException() {
            when(pitchRepository.existsById(pitchId)).thenReturn(true);
            when(bookingDetailRepository.existsByPitch_PitchId(pitchId)).thenReturn(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.deletePitch(pitchId));
            assertTrue(ex.getMessage().contains("đã có đơn đặt sân"));
            verify(pitchRepository, never()).deleteById(any());
        }
    }

    @Nested
    class getAllPitches {
        @Test
        void returnsPagedResults() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<Pitch> page = new PageImpl<>(List.of(pitch), pageable, 1);
            when(pitchRepository.findAll(any(Specification.class), eq(pageable)))
                    .thenReturn(page);

            Page<PitchResponseDTO> result = service.getAllPitches(pageable, null, null, null);

            assertEquals(1, result.getTotalElements());
            assertEquals("Sân số 1", result.getContent().getFirst().getName());
        }
    }

    @Nested
    class getPitchById {
        @Test
        void hasData_ReturnsResponseDTO() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.of(pitch));

            PitchResponseDTO result = service.getPitchById(pitchId);

            assertNotNull(result);
            assertEquals("Sân số 1", result.getName());
        }

        @Test
        void notFound_ThrowsException() {
            when(pitchRepository.findById(pitchId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getPitchById(pitchId));
            assertTrue(ex.getMessage().contains("Cannot find pitch"));
        }
    }
}