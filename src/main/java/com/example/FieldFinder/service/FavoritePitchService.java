package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.res.PitchResponseDTO;

import java.util.List;
import java.util.UUID;

public interface FavoritePitchService {

    /** Thêm sân vào yêu thích. Idempotent: favorite trùng thì bỏ qua. */
    void add(UUID userId, UUID pitchId);

    /** Bỏ sân khỏi yêu thích. Không tồn tại thì bỏ qua. */
    void remove(UUID userId, UUID pitchId);

    /** Danh sách pitchId đã yêu thích (mới nhất trước) — FE đổ trạng thái tim. */
    List<UUID> listIds(UUID userId);

    /** Danh sách sân yêu thích đầy đủ (bỏ qua sân đã xoá), giữ thứ tự mới nhất trước. */
    List<PitchResponseDTO> listPitches(UUID userId);
}
