package com.example.FieldFinder.moderation;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kiểm duyệt tự động bằng luật (rule-based), không phụ thuộc dịch vụ ngoài.
 *
 * Hai nhóm luật:
 *  1) Từ cấm: danh sách nạp từ classpath {@code moderation/banned-words.txt}.
 *     So khớp trên văn bản đã "chuẩn hoá" (bỏ dấu tiếng Việt, gộp ký tự lặp,
 *     loại ký tự chèn) để bắt cả các biến thể né bộ lọc (vd: "đ.c.m", "ddmm").
 *  2) Heuristic: liên kết/quảng cáo, số điện thoại/thông tin liên hệ, spam ký tự lặp.
 *
 * Bình luận rỗng (chỉ chấm sao) luôn được cho qua.
 */
@Service
public class ModerationServiceImpl implements ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationServiceImpl.class);

    private static final String BANNED_WORDS_RESOURCE = "moderation/banned-words.txt";

    // http(s)://, www., hoặc tên miền dạng abc.com/.vn/.net...
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(https?://|www\\.|\\b[a-z0-9-]+\\.(com|vn|net|org|info|xyz|shop|store)\\b)",
            Pattern.CASE_INSENSITIVE);

    // >= 9 chữ số liên tiếp (cho phép xen . - khoảng trắng) -> nghi số điện thoại.
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?:\\d[\\s.\\-]?){9,}");

    // 1 ký tự lặp >= 6 lần -> spam (vd "aaaaaaa", "!!!!!!!").
    private static final Pattern REPEAT_PATTERN = Pattern.compile("(.)\\1{5,}");

    /** Từ cấm đã chuẩn hoá (lowercase, bỏ dấu). */
    private final Set<String> bannedWords = new HashSet<>();

    @PostConstruct
    void loadBannedWords() {
        try {
            ClassPathResource resource = new ClassPathResource(BANNED_WORDS_RESOURCE);
            if (!resource.exists()) {
                log.warn("Không tìm thấy {} — bộ lọc từ cấm rỗng.", BANNED_WORDS_RESOURCE);
                return;
            }
            try (InputStream is = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim();
                    if (word.isEmpty() || word.startsWith("#")) continue;
                    String normalized = normalize(word).replaceAll("\\s+", "");
                    if (!normalized.isEmpty()) bannedWords.add(normalized);
                }
            }
            log.info("Đã nạp {} từ cấm cho kiểm duyệt tự động.", bannedWords.size());
        } catch (Exception e) {
            log.error("Lỗi nạp danh sách từ cấm: {}", e.getMessage());
        }
    }

    @Override
    public ModerationResult moderate(String comment) {
        if (comment == null || comment.isBlank()) {
            return ModerationResult.pass();
        }

        // Heuristic chạy trên văn bản gốc (giữ ký tự gốc để bắt link/số).
        if (LINK_PATTERN.matcher(comment).find()) {
            return ModerationResult.reject("Bình luận chứa liên kết hoặc nội dung quảng cáo.");
        }
        if (PHONE_PATTERN.matcher(comment).find()) {
            return ModerationResult.reject("Bình luận chứa số điện thoại/thông tin liên hệ.");
        }
        if (REPEAT_PATTERN.matcher(comment).find()) {
            return ModerationResult.reject("Bình luận chứa ký tự lặp lại bất thường (spam).");
        }

        // Từ cấm. Tránh false-positive với từ tiếng Việt phổ biến (sau khi bỏ dấu
        // "ngủ" -> "ngu", "các" -> "cac"...): từ ĐƠN khớp NGUYÊN token, cụm NHIỀU TỪ
        // khớp theo chuỗi con. Không dùng so khớp chuỗi con cho từ đơn.
        String normalized = normalize(comment);
        Set<String> tokens = new HashSet<>(Arrays.asList(normalized.split("[^a-z0-9]+")));
        for (String banned : bannedWords) {
            boolean hit = banned.contains(" ")
                    ? normalized.contains(banned)
                    : tokens.contains(banned);
            if (hit) {
                return ModerationResult.reject("Bình luận chứa từ ngữ không phù hợp.");
            }
        }

        return ModerationResult.pass();
    }

    /**
     * Chuẩn hoá tiếng Việt: lowercase, bỏ dấu thanh/dấu mũ, đ -> d,
     * gộp ký tự lặp liên tiếp về 1 (eee -> e) để chống né bộ lọc.
     */
    static String normalize(String input) {
        String lower = input.toLowerCase();
        String noMark = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'd');
        // gộp ký tự lặp: "ddmmmm" -> "dm" (giúp khớp từ cấm dạng kéo dài)
        return noMark.replaceAll("(.)\\1+", "$1");
    }
}
