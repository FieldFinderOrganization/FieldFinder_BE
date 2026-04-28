package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import com.example.FieldFinder.repository.ProviderRepository;
import com.google.api.Http;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.management.RuntimeErrorException;
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

    @InjectMocks
    private ProviderAddressServiceImpl addressService;

    private UUID providerId;
    private UUID addressId;
    private ProviderAddress address;
    private Provider provider;
    private ProviderAddressRequestDTO requestDTO;

    @BeforeEach
    void setUp() {
        providerId = UUID.randomUUID();
        addressId = UUID.randomUUID();

        provider = new Provider();
        provider.setProviderId(providerId);

        address = new ProviderAddress();
        address.setProviderAddressId(addressId);
        address.setAddress("123 Đông Hà");
        address.setProvider(provider);

        requestDTO = new ProviderAddressRequestDTO();
        requestDTO.setAddress("123 Đông Hà");
        requestDTO.setProviderId(providerId);
    }


    @Test
    void addAddress_Success_ReturnsResponseDTO() {
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
        when(addressRepository.save((any(ProviderAddress.class)))).thenReturn(address);

        ProviderAddressResponseDTO result = addressService.addAddress(requestDTO);

        assertNotNull(result);
        assertEquals("123 Đông Hà", result.getAddress());

        verify(addressRepository, times(1)).save((any(ProviderAddress.class)));
    }

    @Test
    void addAddress_ProviderNotFound_ThrowsException() {
        when(providerRepository.findById(providerId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            addressService.addAddress(requestDTO);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Provider not found!"));

        verify(addressRepository, never()).save(any());
    }

    @Test
    void updateAddress_Success_ReturnsResponseDTO() {
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

        requestDTO.setAddress("456 Tây Hồ");

        ProviderAddress updatedAddress = new ProviderAddress();
        updatedAddress.setProviderAddressId(addressId);
        updatedAddress.setAddress("456 Tây Hồ");
        when(addressRepository.save(any(ProviderAddress.class))).thenReturn(updatedAddress);

        ProviderAddressResponseDTO result = addressService.updateAddress(addressId, requestDTO);

        assertNotNull(result);
        assertEquals("456 Tây Hồ", result.getAddress());
        verify(addressRepository, times(1)).save(any(ProviderAddress.class));
    }

    @Test
    void updateAddress_AddressNotFound_ThrowsException() {
        when(addressRepository.findById(addressId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            addressService.updateAddress(addressId, requestDTO);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Address not found!"));

        verify(addressRepository, never()).save(any());
    }

    @Test
    void deleteAddress_AddressNotFound_ThrowsException() {
        when(addressRepository.findById(addressId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            addressService.deleteAddress(addressId);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Address not found!"));

        verify(addressRepository, never()).delete(any());
    }

    @Test
    void deleteAddress_HasBookedPitch_ThrowsException() {
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

        Pitch mockPitch = new Pitch();
        mockPitch.setPitchId(UUID.randomUUID());
        when(pitchRepository.findByProviderAddressProviderAddressId(addressId))
                .thenReturn(List.of(mockPitch));

        when(bookingDetailRepository.existsByPitch_PitchId(mockPitch.getPitchId()))
                .thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            addressService.deleteAddress(addressId);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Không thể xóa khu vực vì có sân đã được đặt!"));

        verify(addressRepository, never()).delete(any());
    }

    @Test
    void deleteAddress_Success() {
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

        Pitch mockPitch = new Pitch();
        mockPitch.setPitchId(UUID.randomUUID());
        when(pitchRepository.findByProviderAddressProviderAddressId(addressId))
                .thenReturn(List.of(mockPitch));

        when(bookingDetailRepository.existsByPitch_PitchId(mockPitch.getPitchId()))
                .thenReturn(false);

        addressService.deleteAddress(addressId);

        verify(addressRepository, times(1)).delete(address);
    }
}