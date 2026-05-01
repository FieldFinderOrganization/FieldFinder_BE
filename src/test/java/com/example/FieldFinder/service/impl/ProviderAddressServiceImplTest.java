package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import com.example.FieldFinder.repository.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderAddressServiceImplTest {
    @Mock
    ProviderAddressRepository addressRepository;
    @Mock
    ProviderRepository providerRepository;
    @Mock
    PitchRepository pitchRepository;
    @Mock
    BookingDetailRepository bookingDetailRepository;

    @InjectMocks
    ProviderAddressServiceImpl addressService;

    private ProviderAddressRequestDTO requestDTO;
    private UUID providerId;
    private UUID addressId;
    private ProviderAddress address;
    private Provider provider;

    @BeforeEach
    void setUp() {
        providerId = UUID.randomUUID();
        addressId = UUID.randomUUID();

        provider = new Provider();
        provider.setProviderId(providerId);

        address = ProviderAddress.builder()
                .providerAddressId(addressId)
                .address("123 Tây Hồ")
                .provider(provider)
                .build();

        requestDTO = createRequestDTO();
    }

    private ProviderAddressRequestDTO createRequestDTO() {
        return ProviderAddressRequestDTO.builder()
                .providerId(providerId)
                .address("123 Tây Hồ")
                .build();
    }

    @Nested
    class addAddress {
        @Test
        void success_ReturnsResponseDTO() {
            when(providerRepository.findById(requestDTO.getProviderId())).thenReturn(Optional.of(provider));
            when(addressRepository.save(any(ProviderAddress.class))).thenReturn(address);

            ProviderAddressResponseDTO result = addressService.addAddress(requestDTO);

            assertNotNull(result);
            assertEquals("123 Tây Hồ", result.getAddress());

            verify(addressRepository, times(1)).save(any(ProviderAddress.class));
        }

        @Test
        void providerNotFound_ThrowsException() {
            when(providerRepository.findById(requestDTO.getProviderId())).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> addressService.addAddress(requestDTO));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            assertNotNull(ex.getReason());
            assertTrue(ex.getReason().contains("Provider not found!"));

            verify(addressRepository, never()).save(any(ProviderAddress.class));
        }
    }

    @Nested
    class updateAddress {
        @Test
        void success_ReturnsResponseDTO() {
            requestDTO.setAddress("456 Đông Anh");
            when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

            ProviderAddress updatedAddress = ProviderAddress.builder()
                            .providerAddressId(addressId)
                                    .address("456 Đông Anh")
                                            .provider(provider)
                                                    .build();
            when(addressRepository.save(any(ProviderAddress.class))).thenReturn(updatedAddress);

            ProviderAddressResponseDTO result = addressService.updateAddress(addressId, requestDTO);

            assertNotNull(result);
            assertEquals("456 Đông Anh", result.getAddress());

            verify(addressRepository, times(1)).save(any(ProviderAddress.class));
        }

        @Test
        void addressNotFound_ThrowsException() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> addressService.updateAddress(addressId, requestDTO));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            assertNotNull(ex.getReason());
            assertTrue(ex.getReason().contains("Address not found!"));

            verify(addressRepository, never()).save(any(ProviderAddress.class));
        }
    }

    @Nested
    class deleteAddress {
        @Test
        void success() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

            Pitch mockPitch = new Pitch();
            mockPitch.setPitchId(UUID.randomUUID());
            when(pitchRepository.findByProviderAddressProviderAddressId(addressId)).thenReturn(List.of(mockPitch));

            when(bookingDetailRepository.existsByPitch_PitchId(mockPitch.getPitchId())).thenReturn(false);

            addressService.deleteAddress(addressId);

            verify(addressRepository, times(1)).delete(any(ProviderAddress.class));
        }

        @Test
        void addressNotFound_ThrowsException() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> addressService.deleteAddress(addressId));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            assertNotNull(ex.getReason());
            assertTrue(ex.getReason().contains("Address not found!"));

            verify(addressRepository, never()).delete(any(ProviderAddress.class));
        }

        @Test
        void hasBookedPitch_ThrowsException() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

            Pitch mockPitch = new Pitch();
            mockPitch.setPitchId(UUID.randomUUID());
            when(pitchRepository.findByProviderAddressProviderAddressId(addressId)).thenReturn(List.of(mockPitch));

            when(bookingDetailRepository.existsByPitch_PitchId(mockPitch.getPitchId())).thenReturn(true);

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> addressService.deleteAddress(addressId));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertNotNull(ex.getReason());
            assertTrue(ex.getReason().contains("Không thể xóa khu vực vì có sân đã được đặt!"));

            verify(addressRepository, never()).delete(any(ProviderAddress.class));
        }
    }

    @Nested
    class getAddressesByProvider {
        @Test
        void hasData_ReturnsList() {
            when(addressRepository.findByProviderProviderId(providerId)).thenReturn(List.of(address));

            List<ProviderAddressResponseDTO> result = addressService.getAddressesByProvider(providerId);

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("123 Tây Hồ", result.getFirst().getAddress());

            verify(addressRepository, times(1)).findByProviderProviderId(providerId);
        }

        @Test
        void hasNoData_ReturnsEmptyList() {
            when(addressRepository.findByProviderProviderId(providerId)).thenReturn(List.of());

            List<ProviderAddressResponseDTO> result = addressService.getAddressesByProvider(providerId);

            assertNotNull(result);
            assertTrue(result.isEmpty());

            verify(addressRepository, times(1)).findByProviderProviderId(providerId);
        }
    }

    @Nested
    class getAllAddresses {
        @Test
        void hasData_ReturnsList() {
            when(addressRepository.findAll()).thenReturn(List.of(address));

            List<ProviderAddressResponseDTO> result = addressService.getAllAddresses();

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("123 Tây Hồ", result.getFirst().getAddress());

            verify(addressRepository, times(1)).findAll();
        }

        @Test
        void hasNoData_ReturnsEmptyList() {
            when(addressRepository.findAll()).thenReturn(List.of());

            List<ProviderAddressResponseDTO> result = addressService.getAllAddresses();

            assertNotNull(result);
            assertTrue(result.isEmpty());

            verify(addressRepository, times(1)).findAll();
        }
    }
}