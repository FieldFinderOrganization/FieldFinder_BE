package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.PitchRequestDTO;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PitchService {
    PitchResponseDTO createPitch(PitchRequestDTO dto);
    PitchResponseDTO updatePitch(UUID pitchId, PitchRequestDTO dto);
    List<PitchResponseDTO> getPitchesByProviderAddressId(UUID providerAddressId);
    void deletePitch(UUID pitchId);
    Page<PitchResponseDTO> getAllPitches(Pageable pageable, String district, String type, String name);

    PitchResponseDTO getPitchById(UUID pitchId);

    /** Seed toạ độ riêng cho sân cũ: jitter quanh tâm khu vực, vẫn trong quận. Trả số sân đã cập nhật. */
    int backfillPitchCoordinates();

    void evictAllListPitchCaches();

    /**
     * Ngưng hoạt động sân từ targetDate.
     * - Block nếu có booking CONFIRMED với bookingDate >= targetDate.
     * - Tự động hủy PENDING với bookingDate >= targetDate và gửi notification.
     * @param isAdmin true = admin (quản lý tất cả sân), false = provider (chỉ sân của mình)
     */
    void deactivatePitch(UUID pitchId, LocalDate targetDate, UUID requesterId, boolean isAdmin);

    /** Bật lại sân INACTIVE thành ACTIVE. Không có điều kiện block. */
    void reactivatePitch(UUID pitchId, UUID requesterId, boolean isAdmin);
}
