package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.ProviderAddressRequestDTO;
import com.example.FieldFinder.dto.res.ProviderAddressResponseDTO;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.service.GeocodingService;
import com.example.FieldFinder.service.ProviderAddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ProviderAddressServiceImpl implements ProviderAddressService {
    private final ProviderAddressRepository addressRepository;
    private final ProviderRepository providerRepository;
    private final PitchRepository pitchRepository;
    private final BookingDetailRepository bookingDetailRepository;
    private final GeocodingService geocodingService;

    public ProviderAddressServiceImpl(ProviderAddressRepository addressRepository,
                                      ProviderRepository providerRepository,
                                      PitchRepository pitchRepository,
                                      BookingDetailRepository bookingDetailRepository,
                                      GeocodingService geocodingService) {
        this.addressRepository = addressRepository;
        this.providerRepository = providerRepository;
        this.pitchRepository = pitchRepository;
        this.bookingDetailRepository = bookingDetailRepository;
        this.geocodingService = geocodingService;
    }

    @Override
    public int backfillMissingCoordinates() {
        List<ProviderAddress> missing = addressRepository.findByLatitudeIsNullOrLongitudeIsNull();
        log.info("[GEO-BACKFILL] {} addresses missing coordinates", missing.size());
        int updated = 0;
        for (ProviderAddress addr : missing) {
            try {
                var opt = geocodingService.geocode(addr.getAddress());
                if (opt.isPresent()) {
                    addr.setLatitude(opt.get().latitude());
                    addr.setLongitude(opt.get().longitude());
                    addressRepository.save(addr);
                    updated++;
                    log.info("[GEO-BACKFILL] OK '{}' -> {},{}", addr.getAddress(),
                            opt.get().latitude(), opt.get().longitude());
                } else {
                    log.warn("[GEO-BACKFILL] no result for '{}'", addr.getAddress());
                }
            } catch (Exception e) {
                log.warn("[GEO-BACKFILL] failed '{}': {}", addr.getAddress(), e.getMessage());
            }
            // Nominatim giới hạn ~1 req/giây — tôn trọng để không bị chặn.
            try { Thread.sleep(1100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("[GEO-BACKFILL] done, updated {}/{}", updated, missing.size());
        return updated;
    }

    private void geocodeAndPersist(ProviderAddress address) {
        geocodingService.geocodeAsync(address.getAddress()).thenAccept(opt -> opt.ifPresent(latLng -> {
            address.setLatitude(latLng.latitude());
            address.setLongitude(latLng.longitude());
            addressRepository.save(address);
        }));
    }

    @Override
    public ProviderAddressResponseDTO addAddress(ProviderAddressRequestDTO addressRequestDTO) {
        Provider provider = providerRepository.findById(addressRequestDTO.getProviderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found!"));

        ProviderAddress address = new ProviderAddress();
        address.setAddress(addressRequestDTO.getAddress());
        address.setProvider(provider);

        boolean hasCoords = addressRequestDTO.getLatitude() != null
                && addressRequestDTO.getLongitude() != null;
        if (hasCoords) {
            address.setLatitude(addressRequestDTO.getLatitude());
            address.setLongitude(addressRequestDTO.getLongitude());
        }
        address = addressRepository.save(address);
        // Chỉ geocode khi không có toạ độ chính xác từ map picker.
        if (!hasCoords) geocodeAndPersist(address);
        return ProviderAddressResponseDTO.fromEntity(address);
    }

    @Override
    public ProviderAddressResponseDTO updateAddress(UUID addressId, ProviderAddressRequestDTO addressRequestDTO) {
        ProviderAddress address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found!"));

        boolean addressChanged = !addressRequestDTO.getAddress().equals(address.getAddress());
        address.setAddress(addressRequestDTO.getAddress());

        boolean hasCoords = addressRequestDTO.getLatitude() != null
                && addressRequestDTO.getLongitude() != null;
        if (hasCoords) {
            // Toạ độ chính xác từ map picker → set thẳng, không geocode.
            address.setLatitude(addressRequestDTO.getLatitude());
            address.setLongitude(addressRequestDTO.getLongitude());
            address = addressRepository.save(address);
        } else if (addressChanged) {
            // Đổi địa chỉ mà không kèm toạ độ → xoá + geocode lại.
            address.setLatitude(null);
            address.setLongitude(null);
            address = addressRepository.save(address);
            geocodeAndPersist(address);
        } else {
            address = addressRepository.save(address);
        }
        return ProviderAddressResponseDTO.fromEntity(address);
    }

    @Override
    public void deleteAddress(UUID addressId) {
        ProviderAddress address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found!"));

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