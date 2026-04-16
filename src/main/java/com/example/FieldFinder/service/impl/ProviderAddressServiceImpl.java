package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.service.ProviderAddressService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ProviderAddressServiceImpl implements ProviderAddressService {
    private final ProviderAddressRepository addressRepository;
    private final ProviderRepository providerRepository;
    private final PitchRepository pitchRepository;
    private final BookingDetailRepository bookingDetailRepository;

    public ProviderAddressServiceImpl(ProviderAddressRepository addressRepository,
                                      ProviderRepository providerRepository,
                                      PitchRepository pitchRepository,
                                      BookingDetailRepository bookingDetailRepository) {
        this.addressRepository = addressRepository;
        this.providerRepository = providerRepository;
        this.pitchRepository = pitchRepository;
        this.bookingDetailRepository = bookingDetailRepository;
    }

    @Override
    public ProviderAddressResponseDTO addAddress(ProviderAddressRequestDTO addressRequestDTO) {
        Provider provider = providerRepository.findById(addressRequestDTO.getProviderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found!"));

        ProviderAddress address = new ProviderAddress();
        address.setAddress(addressRequestDTO.getAddress());
        address.setProvider(provider);

        address = addressRepository.save(address);
        return ProviderAddressResponseDTO.fromEntity(address);
    }

    @Override
    public ProviderAddressResponseDTO updateAddress(UUID addressId, ProviderAddressRequestDTO addressRequestDTO) {
        ProviderAddress address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found!"));

        address.setAddress(addressRequestDTO.getAddress());
        address = addressRepository.save(address);
        return ProviderAddressResponseDTO.fromEntity(address);
    }

    @Override
    public void deleteAddress(UUID addressId) {
        ProviderAddress address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found!"));

        // Check if any pitch in this address has bookings
        boolean hasBookedPitches = pitchRepository.findByProviderAddressProviderAddressId(addressId).stream()
                .anyMatch(pitch -> bookingDetailRepository.existsByPitch_PitchId(pitch.getPitchId()));

        if (hasBookedPitches) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể xóa khu vực vì có sân đã được đặt!");
        }

        addressRepository.delete(address);
    }

    @Override
    public List<ProviderAddressResponseDTO> getAddressesByProvider(UUID providerId) {
        List<ProviderAddress> addresses = addressRepository.findByProviderProviderId(providerId);

        return addresses.stream()
                .map(addr -> new ProviderAddressResponseDTO(addr.getProviderAddressId(), addr.getAddress()))
                .toList();
    }

    @Override
    public List<ProviderAddressResponseDTO> getAllAddresses() {
        List<ProviderAddress> addresses = addressRepository.findAll();
        return addresses.stream()
                .map(addr -> new ProviderAddressResponseDTO(addr.getProviderAddressId(), addr.getAddress()))
                .toList();
    }

}