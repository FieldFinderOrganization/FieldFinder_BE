package com.example.FieldFinder.service.impl;


import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.CancelActor;
import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.CachedPage;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.exception.PitchDeactivateBlockedException;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ProviderAddressRepository;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.service.DiscountUsageService;
import com.example.FieldFinder.service.GeocodingService;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.specification.PitchSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PitchServiceImpl implements PitchService {

    private final PitchRepository pitchRepository;
    private final ProviderAddressRepository providerAddressRepository;
    private final BookingDetailRepository bookingDetailRepository;
    private final BookingRepository bookingRepository;
    private final ProviderRepository providerRepository;
    private final DiscountUsageService discountUsageService;
    private final NotificationService notificationService;
    private final CacheManager cacheManager;
    private final GeocodingService geocodingService;
    private final PitchServiceImpl self;

    public PitchServiceImpl(
            PitchRepository pitchRepository,
            ProviderAddressRepository providerAddressRepository,
            BookingDetailRepository bookingDetailRepository,
            BookingRepository bookingRepository,
            ProviderRepository providerRepository,
            DiscountUsageService discountUsageService,
            NotificationService notificationService,
            CacheManager cacheManager,
            GeocodingService geocodingService,
            @Lazy PitchServiceImpl self) {
        this.pitchRepository = pitchRepository;
        this.providerAddressRepository = providerAddressRepository;
        this.bookingDetailRepository = bookingDetailRepository;
        this.bookingRepository = bookingRepository;
        this.providerRepository = providerRepository;
        this.discountUsageService = discountUsageService;
        this.notificationService = notificationService;
        this.cacheManager = cacheManager;
        this.geocodingService = geocodingService;
        this.self = self;
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "pitches_list", allEntries = true)
    })
    public PitchResponseDTO createPitch(PitchRequestDTO dto) {
        ProviderAddress providerAddress = providerAddressRepository.findById(dto.getProviderAddressId())
                .orElseThrow(() -> new RuntimeException("ProviderAddress not found!"));

        Pitch pitch = Pitch.builder()
                .providerAddress(providerAddress)
                .name(dto.getName())
                .type(dto.getType())
                .price(dto.getPrice())
                .environment(dto.getEnvironment())
                .description(dto.getDescription())
                .imageUrls(dto.getImageUrls() != null ? dto.getImageUrls() : new ArrayList<>())
                // Toạ độ chốt 1 lần khi tạo; null thì DTO fallback toạ độ khu vực.
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .build();

        pitch = pitchRepository.save(pitch);
        return PitchResponseDTO.fromEntity(pitch);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "pitches_list", allEntries = true),
            @CacheEvict(value = "pitch_detail", key = "#pitchId")
    })
    public PitchResponseDTO updatePitch(UUID pitchId, PitchRequestDTO dto) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Pitch not found!"));
        pitch.setName(dto.getName());
        pitch.setType(dto.getType());
        pitch.setEnvironment(dto.getEnvironment());
        pitch.setPrice(dto.getPrice());
        pitch.setDescription(dto.getDescription());
        pitch.setImageUrls(dto.getImageUrls() != null ? dto.getImageUrls() : new ArrayList<>());
        pitch = pitchRepository.save(pitch);
        return PitchResponseDTO.fromEntity(pitch);
    }

    @Override
    public List<PitchResponseDTO> getPitchesByProviderAddressId(UUID providerAddressId) {
        return pitchRepository.findByProviderAddressProviderAddressId(providerAddressId)
                .stream().map(PitchResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "pitches_list", allEntries = true),
            @CacheEvict(value = "pitch_detail", key = "#pitchId")
    })
    public void deletePitch(UUID pitchId) {
        if (!pitchRepository.existsById(pitchId)) {
            throw new RuntimeException("Pitch not found!");
        }
        if (bookingDetailRepository.existsByPitch_PitchId(pitchId)) {
            throw new RuntimeException("Không thể xóa sân vì đã có đơn đặt sân liên quan!");
        }
        pitchRepository.deleteById(pitchId);
    }

    @Override
    public Page<PitchResponseDTO> getAllPitches(Pageable pageable, String district, String type, String name) {
        return self.getAllPitchesCached(pageable, district, type, name).toPage();
    }

    @Cacheable(value = "pitches_list", keyGenerator = "pitchListCacheKeyGenerator")
    public CachedPage<PitchResponseDTO> getAllPitchesCached(Pageable pageable, String district, String type, String name) {
        Specification<Pitch> spec = Specification.<Pitch>unrestricted()
                .and(PitchSpecification.hasDistrict(district))
                .and(PitchSpecification.hasType(type))
                .and(PitchSpecification.hasName(name))
                .and((root, query, cb) -> cb.equal(root.get("status"), Pitch.PitchStatus.ACTIVE)); // chỉ hiển sân đang hoạt động

        Page<Pitch> pitches = pitchRepository.findAll(spec, pageable);

        List<PitchResponseDTO> pitchResponseDTOS = pitches.getContent()
                .stream()
                .map(PitchResponseDTO::fromEntity)
                .toList();

        return CachedPage.from(new PageImpl<>(pitchResponseDTOS, pageable, pitches.getTotalElements()));
    }

    @Override
    @Cacheable(value = "pitch_detail", key = "#pitchId")
    public PitchResponseDTO getPitchById(UUID pitchId) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Cannot find pitch with id: " + pitchId));

        return PitchResponseDTO.fromEntity(pitch);
    }

    @Override
    public int backfillPitchCoordinates() {
        List<Pitch> missing = pitchRepository.findByLatitudeIsNullOrLongitudeIsNull();
        log.info("[PITCH-GEO] {} sân thiếu toạ độ riêng", missing.size());
        int updated = 0;
        for (Pitch pitch : missing) {
            ProviderAddress pa = pitch.getProviderAddress();
            Double anchorLat = pa.getLatitude();
            Double anchorLng = pa.getLongitude();

            // Khu vực chưa có tâm → geocode địa chỉ rồi lưu lại để tái dùng.
            if (anchorLat == null || anchorLng == null) {
                var opt = geocodingService.geocode(pa.getAddress());
                if (opt.isEmpty()) {
                    log.warn("[PITCH-GEO] bỏ qua '{}': khu vực '{}' không geocode được",
                            pitch.getName(), pa.getAddress());
                    continue;
                }
                anchorLat = opt.get().latitude();
                anchorLng = opt.get().longitude();
                pa.setLatitude(anchorLat);
                pa.setLongitude(anchorLng);
                providerAddressRepository.save(pa);
            }

            // Jitter tất định theo pitchId: bán kính 150–400m quanh tâm → vẫn trong quận.
            Random rnd = new Random(pitch.getPitchId().hashCode());
            double radiusM = 150 + rnd.nextDouble() * 250;
            double bearing = rnd.nextDouble() * 2 * Math.PI;
            double dyM = radiusM * Math.cos(bearing);
            double dxM = radiusM * Math.sin(bearing);
            double lat = anchorLat + dyM / 111_320.0;
            double lng = anchorLng + dxM / (111_320.0 * Math.cos(Math.toRadians(anchorLat)));

            pitch.setLatitude(lat);
            pitch.setLongitude(lng);
            pitchRepository.save(pitch);
            updated++;
            log.info("[PITCH-GEO] OK '{}' -> {},{}", pitch.getName(), lat, lng);
        }
        self.evictAllListPitchCaches();
        log.info("[PITCH-GEO] xong, cập nhật {}/{}", updated, missing.size());
        return updated;
    }

    @Override
    public void evictAllListPitchCaches() {
        for (String name : new String[]{"pitches_list", "pitch_detail"}) {
            Cache c = cacheManager.getCache(name);
            if (c != null) {
                c.clear();
            }
        }
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "pitches_list", allEntries = true),
            @CacheEvict(value = "pitch_detail", key = "#pitchId")
    })
    public void deactivatePitch(UUID pitchId, LocalDate targetDate, UUID requesterId, boolean isAdmin) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Pitch not found!"));

        // Ownership check cho Provider
        if (!isAdmin) {
            var provider = providerRepository.findByUser_UserId(requesterId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy thông tin chủ sân!"));
            boolean owns = pitch.getProviderAddress() != null
                    && pitch.getProviderAddress().getProvider() != null
                    && provider.getProviderId().equals(
                            pitch.getProviderAddress().getProvider().getProviderId());
            if (!owns) {
                throw new RuntimeException("Bạn không có quyền ngưng sân này!");
            }
        }

        // Kiểm tra block: có CONFIRMED nào >= targetDate không?
        if (bookingDetailRepository.existsConfirmedOnOrAfter(pitchId, targetDate)) {
            long count = bookingDetailRepository.countConfirmedOnOrAfter(pitchId, targetDate);
            LocalDate maxDate = bookingDetailRepository.findMaxConfirmedBookingDateOnOrAfter(pitchId, targetDate);
            LocalDate earliest = maxDate != null ? maxDate.plusDays(1) : targetDate;
            throw new PitchDeactivateBlockedException((int) count, earliest);
        }

        // Auto-cancel tất cả PENDING >= targetDate
        List<Booking> pendingToCancel = bookingDetailRepository.findPendingBookingsOnOrAfter(pitchId, targetDate);
        for (Booking booking : pendingToCancel) {
            booking.setStatus(BookingStatus.CANCELED);
            booking.setCancelledBy(CancelActor.SYSTEM);
            booking.setCancelReason("Sân tạm ngưng hoạt động từ " + targetDate);
            bookingRepository.save(booking);
            // Hoàn voucher nếu có
            discountUsageService.revertForBooking(booking.getBookingId());
            // Thông báo cho khách
            if (booking.getUser() != null) {
                notificationService.notify(
                        booking.getUser().getUserId(),
                        "PITCH_DEACTIVATED",
                        "Lịch đặt sân bị hủy",
                        "Sân \"" + pitch.getName() + "\" tạm ngưng hoạt động từ ngày "
                                + targetDate + ". Lịch đặt ngày "
                                + booking.getBookingDate() + " đã được hủy tự động.",
                        "BOOKING", booking.getBookingId().toString());
            }
        }

        pitch.setStatus(Pitch.PitchStatus.INACTIVE);
        pitchRepository.save(pitch);
        log.info("[PITCH] Đã ngưng sân {} từ {} (hủy {} PENDING)", pitch.getName(), targetDate, pendingToCancel.size());
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "pitches_list", allEntries = true),
            @CacheEvict(value = "pitch_detail", key = "#pitchId")
    })
    public void reactivatePitch(UUID pitchId, UUID requesterId, boolean isAdmin) {
        Pitch pitch = pitchRepository.findById(pitchId)
                .orElseThrow(() -> new RuntimeException("Pitch not found!"));

        if (!isAdmin) {
            var provider = providerRepository.findByUser_UserId(requesterId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy thông tin chủ sân!"));
            boolean owns = pitch.getProviderAddress() != null
                    && pitch.getProviderAddress().getProvider() != null
                    && provider.getProviderId().equals(
                            pitch.getProviderAddress().getProvider().getProviderId());
            if (!owns) {
                throw new RuntimeException("Bạn không có quyền kích hoạt lại sân này!");
            }
        }

        pitch.setStatus(Pitch.PitchStatus.ACTIVE);
        pitchRepository.save(pitch);
        log.info("[PITCH] Đã kích hoạt lại sân {}", pitch.getName());
    }
}
