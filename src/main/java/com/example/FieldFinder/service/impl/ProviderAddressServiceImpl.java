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
                // AUTO-GOM: nếu đã có address CÙNG TÊN với toạ độ → tái dùng, không gọi API.
                // (2 provider chọn cùng "Thủ Đức" = cùng nơi = cùng toạ độ, geocode 1 lần.)
                var sib = siblingCoords(addr.getAddress(), addr.getProviderAddressId());
                if (sib.isPresent()) {
                    addr.setLatitude(sib.get().latitude());
                    addr.setLongitude(sib.get().longitude());
                    addressRepository.save(addr);
                    updated++;
                    log.info("[GEO-BACKFILL] gom '{}' theo address trùng -> {},{}", addr.getAddress(),
                            sib.get().latitude(), sib.get().longitude());
                    continue; // không gọi Nominatim, không cần sleep
                }
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

    /** Canonical hoá address để so trùng (trim + gộp khoảng trắng + thường hoá). */
    private static String canonAddress(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * Toạ độ của một provider_address CÙNG TÊN (canonical) đã có sẵn — null nếu chưa có.
     * Cơ sở "auto-gom": cùng chuỗi address = cùng địa danh = dùng chung 1 toạ độ.
     */
    private java.util.Optional<GeocodingService.LatLng> siblingCoords(String address, UUID excludeId) {
        if (address == null || address.isBlank()) return java.util.Optional.empty();
        String key = canonAddress(address);
        return addressRepository.findAll().stream()
                .filter(a -> excludeId == null || !a.getProviderAddressId().equals(excludeId))
                .filter(a -> a.getLatitude() != null && a.getLongitude() != null)
                .filter(a -> canonAddress(a.getAddress()).equals(key))
                .findFirst()
                .map(a -> new GeocodingService.LatLng(a.getLatitude(), a.getLongitude()));
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
        } else {
            // Không có toạ độ map → resolve đồng bộ (gom theo tên / geocode); fail = từ chối (chặn fake).
            GeocodingService.LatLng resolved = resolveCoordsOrThrow(address.getAddress(), null);
            address.setLatitude(resolved.latitude());
            address.setLongitude(resolved.longitude());
        }
        address = addressRepository.save(address);
        return ProviderAddressResponseDTO.fromEntity(address);
    }

    /**
     * Toạ độ cho address: ưu tiên gom theo tên (sibling), else geocode. KHÔNG geocode được -> ném 400.
     * Chặn provider nhập địa chỉ rác / không tồn tại.
     */
    private GeocodingService.LatLng resolveCoordsOrThrow(String address, UUID excludeId) {
        var sib = siblingCoords(address, excludeId);
        if (sib.isPresent()) return sib.get();
        var opt = geocodingService.geocode(address);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không xác định được vị trí cho địa chỉ này. Vui lòng chọn vị trí trên bản đồ, "
                    + "hoặc nhập rõ hơn (kèm quận/huyện và tỉnh/thành).");
        }
        return opt.get();
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
            // Đổi địa chỉ mà không kèm toạ độ → resolve đồng bộ, fail = từ chối (chặn fake).
            GeocodingService.LatLng resolved = resolveCoordsOrThrow(address.getAddress(), address.getProviderAddressId());
            address.setLatitude(resolved.latitude());
            address.setLongitude(resolved.longitude());
            address = addressRepository.save(address);
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

    @Override
    public List<String> getDistinctAreas() {
        // Phân biệt theo canonical (trim + thường hoá) nhưng GIỮ nguyên văn bản gốc đầu tiên gặp,
        // sắp xếp alpha — danh sách cho dropdown chọn khu vực.
        java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
        for (ProviderAddress a : addressRepository.findAll()) {
            String raw = a.getAddress();
            if (raw == null || raw.isBlank()) continue;
            seen.putIfAbsent(canonAddress(raw), raw.trim());
        }
        List<String> out = new java.util.ArrayList<>(seen.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}