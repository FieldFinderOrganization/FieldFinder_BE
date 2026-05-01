package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderServiceImplTest {

    @Mock ProviderRepository providerRepository;
    @Mock UserRepository userRepository;

    @InjectMocks ProviderServiceImpl service;

    private UUID userId;
    private UUID providerId;
    private User user;
    private Provider provider;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        providerId = UUID.randomUUID();

        user = new User();
        user.setUserId(userId);

        provider = Provider.builder()
                .providerId(providerId)
                .user(user)
                .cardNumber("9876543210")
                .bank("VCB")
                .build();
    }

    private ProviderRequestDTO buildRequest() {
        ProviderRequestDTO dto = new ProviderRequestDTO();
        dto.setUserId(userId);
        dto.setCardNumber("9876543210");
        dto.setBank("VCB");
        return dto;
    }

    @Nested
    class createProvider {
        @Test
        void valid_savesAndReturnsDTO() {
            when(userRepository.findByUserId(userId)).thenReturn(Optional.of(user));
            when(providerRepository.save(any(Provider.class))).thenReturn(provider);

            ProviderResponseDTO result = service.createProvider(buildRequest());

            assertNotNull(result);
            assertEquals("VCB", result.getBank());
            assertEquals("9876543210", result.getCardNumber());
            verify(providerRepository).save(any(Provider.class));
        }

        @Test
        void userNotFound_ThrowsException() {
            when(userRepository.findByUserId(userId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createProvider(buildRequest()));
            assertTrue(ex.getMessage().contains("Không tìm thấy người dùng"));
            verify(providerRepository, never()).save(any());
        }
    }

    @Nested
    class updateProvider {
        @Test
        void existing_updatesAndReturnsDTO() {
            when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
            when(providerRepository.save(any(Provider.class))).thenAnswer(inv -> inv.getArgument(0));

            ProviderRequestDTO req = buildRequest();
            req.setCardNumber("1111222233334444");
            req.setBank("TCB");

            ProviderResponseDTO result = service.updateProvider(providerId, req);

            assertNotNull(result);
            assertEquals("1111222233334444", provider.getCardNumber());
            assertEquals("TCB", provider.getBank());
        }

        @Test
        void notFound_ThrowsException() {
            when(providerRepository.findById(providerId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updateProvider(providerId, buildRequest()));
            assertTrue(ex.getMessage().contains("Provider not found"));
        }
    }

    @Nested
    class getProviderByUserId {
        @Test
        void hasData_ReturnsDTO() {
            when(providerRepository.findByUser_UserId(userId)).thenReturn(Optional.of(provider));

            ProviderResponseDTO result = service.getProviderByUserId(userId);

            assertNotNull(result);
            assertEquals(userId, result.getUserId());
        }

        @Test
        void notFound_ThrowsException() {
            when(providerRepository.findByUser_UserId(userId)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getProviderByUserId(userId));
            assertTrue(ex.getMessage().contains("Provider not found for userId"));
        }
    }

    @Nested
    class getAllProviders {
        @Test
        void hasData_ReturnsList() {
            when(providerRepository.findAll()).thenReturn(List.of(provider));

            List<ProviderResponseDTO> result = service.getAllProviders();

            assertEquals(1, result.size());
            assertEquals(providerId, result.getFirst().getProviderId());
        }

        @Test
        void empty_ReturnsEmpty() {
            when(providerRepository.findAll()).thenReturn(List.of());

            assertTrue(service.getAllProviders().isEmpty());
        }
    }
}