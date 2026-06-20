package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderRequestDTO;
import com.example.FieldFinder.dto.res.ProviderResponseDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.ProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProviderServiceImpl implements ProviderService {

    private final ProviderRepository providerRepository;
    private final UserRepository userRepository;

    @Override
    public ProviderResponseDTO createProvider(ProviderRequestDTO dto) {
        User user = userRepository.findByUserId(dto.getUserId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng với id: " + dto.getUserId()));

        Provider provider = Provider.builder()
                .user(user)
                .build();
        provider = providerRepository.save(provider);
        return mapToDto(provider);
    }

    @Override
    public ProviderResponseDTO updateProvider(UUID providerId, ProviderRequestDTO dto) {
        // Provider không còn field TK ngân hàng (chuyển sang BankAccount theo userId).
        // Giữ endpoint để xác thực tồn tại provider; không còn gì để cập nhật trực tiếp.
        Provider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new RuntimeException("Provider not found!"));
        return mapToDto(provider);
    }

    @Override
    public ProviderResponseDTO getProviderByUserId(UUID userId) {
        // Auto-create an empty provider profile the first time a field owner (PROVIDER)
        // opens "Quản lý kinh doanh". Without this, accounts that have the PROVIDER role
        // but no providers row would get a 400 (RuntimeException -> BAD_REQUEST).
        Provider provider = providerRepository.findByUser_UserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findByUserId(userId)
                            .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng với id: " + userId));
                    if (user.getRole() != User.Role.PROVIDER) {
                        throw new RuntimeException("Người dùng không phải là chủ sân (provider): " + userId);
                    }
                    Provider created = Provider.builder().user(user).build();
                    return providerRepository.save(created);
                });
        return mapToDto(provider);
    }

    @Override
    public List<ProviderResponseDTO> getAllProviders() {
        return providerRepository.findAll().stream()
                .map(this::mapToDto)
                .toList();
    }


    private ProviderResponseDTO mapToDto(Provider provider) {
        ProviderResponseDTO dto = new ProviderResponseDTO();
        dto.setProviderId(provider.getProviderId());

        if (provider.getUser() != null) {
            dto.setUserId(provider.getUser().getUserId());
        }
        return dto;
    }
}
