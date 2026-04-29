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
    private ProviderAddressRepository addressRepository;
    @Mock
    private ProviderRepository providerRepository;
    @Mock
    private PitchRepository pitchRepository;
    @Mock
    private BookingDetailRepository bookingDetailRepository;

    private Provider provider;
    private UUID providerId;
    private ProviderAddress address;
    private UUID addressId;
    private ProviderAddressRequestDTO requestDTO;

    @InjectMocks
    private ProviderAddressServiceImpl addressService;

    @BeforeEach
    void setUp() {
        providerId = UUID.randomUUID();
        provider = new Provider();
        provider.setProviderId(providerId);

        addressId = UUID.randomUUID();
        address = new ProviderAddress();
        address.setProviderAddressId(addressId);
        address.setAddress("123 Đông Anh");
        address.setProvider(provider);

        requestDTO = new ProviderAddressRequestDTO();
        requestDTO.setAddress("123 Đông Anh");
        requestDTO.setProviderId(providerId);
    }

    @Nested
    class addAddress {
        @Test
        void success_ReturnsResponseDTO() {
            when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
            when(addressRepository.save(any(ProviderAddress.class))).thenReturn(address);

            ProviderAddressResponseDTO result = addressService.addAddress(requestDTO);

            assertNotNull(result);
            assertEquals("123 Đông Anh", result.getAddress());

            verify(addressRepository, times(1)).save(any(ProviderAddress.class));
        }

        @Test
        void providerNotFound_ThrowsException() {
            when(providerRepository.findById(providerId)).thenReturn(Optional.empty());

            ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> addressService.addAddress(requestDTO));

            assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
            assertNotNull(exception.getReason());
            assertTrue(exception.getReason().contains("Provider not found!"));

            verify(addressRepository, never()).save(any(ProviderAddress.class));
        }
    }

    @Nested
    class updateAddress {
        @Test
        void success_ReturnsResponseDTO() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

            requestDTO.setAddress("456 Tây Hồ");

            ProviderAddress updatedAddress = new ProviderAddress();
            updatedAddress.setProviderAddressId(addressId);
            updatedAddress.setAddress("456 Tây Hồ");
            updatedAddress.setProvider(provider);
            when(addressRepository.save(any(ProviderAddress.class))).thenReturn(updatedAddress);

            ProviderAddressResponseDTO result = addressService.updateAddress(addressId, requestDTO);

            assertNotNull(result);
            assertEquals("456 Tây Hồ", result.getAddress());

            verify(addressRepository, times(1)).save(any(ProviderAddress.class));
        }

        @Test
        void addressNotFound_ThrowsException() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.empty());

            ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> addressService.updateAddress(addressId, requestDTO));

            assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
            assertNotNull(exception.getReason());
            assertTrue(exception.getReason().contains("Address not found!"));

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
            when(pitchRepository.findByProviderAddressProviderAddressId(addressId))
                    .thenReturn(List.of(mockPitch));

            when(bookingDetailRepository.existsByPitch_PitchId(mockPitch.getPitchId()))
                    .thenReturn(Boolean.FALSE);

            addressService.deleteAddress(addressId);

            verify(addressRepository, times(1)).delete(any(ProviderAddress.class));
        }

        @Test
        void addressNotFound_ThrowsException() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.empty());

            ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> addressService.deleteAddress(addressId));

            assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
            assertNotNull(exception.getReason());
            assertTrue(exception.getReason().contains("Address not found!"));

            verify(addressRepository, never()).delete(any(ProviderAddress.class));
        }

        @Test
        void hasBookedPitch_ThrowsException() {
            when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

            Pitch mockPitch = new Pitch();
            mockPitch.setPitchId(UUID.randomUUID());
            when(pitchRepository.findByProviderAddressProviderAddressId(addressId))
                    .thenReturn(List.of(mockPitch));

            when(bookingDetailRepository.existsByPitch_PitchId(mockPitch.getPitchId()))
                    .thenReturn(Boolean.TRUE);

            ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> addressService.deleteAddress(addressId));

            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
            assertNotNull(exception.getReason());
            assertTrue(exception.getReason().contains("Không thể xóa khu vực vì có sân đã được đặt!"));

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

            assertEquals(addressId, result.getFirst().getProviderAddressId());
            assertEquals("123 Đông Anh", result.getFirst().getAddress());

            verify(addressRepository, times(1)).findByProviderProviderId(providerId);
        }

        @Test
        void noData_ReturnsEmptyList() {
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

            assertEquals(addressId, result.getFirst().getProviderAddressId());
            assertEquals("123 Đông Anh", result.getFirst().getAddress());

            verify(addressRepository, times(1)).findAll();
        }

        @Test
        void noData_ReturnsEmptyList() {
            when(addressRepository.findAll()).thenReturn(List.of());

            List<ProviderAddressResponseDTO> result = addressService.getAllAddresses();

            assertNotNull(result);
            assertTrue(result.isEmpty());

            verify(addressRepository, times(1)).findAll();
        }
    }
}