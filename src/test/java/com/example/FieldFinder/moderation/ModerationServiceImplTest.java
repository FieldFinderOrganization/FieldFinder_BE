package com.example.FieldFinder.moderation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử bộ kiểm duyệt tự động rule-based.
 *
 * Nạp danh sách từ cấm thật từ classpath (moderation/banned-words.txt) qua
 * {@link ModerationServiceImpl#loadBannedWords()} — không mock, để test phản ánh đúng
 * hành vi runtime. Mỗi ID tương ứng test case trong bảng kiểm thử (nhóm A–H).
 */
class ModerationServiceImplTest {

    private static ModerationServiceImpl moderation;

    @BeforeAll
    static void setUp() {
        moderation = new ModerationServiceImpl();
        moderation.loadBannedWords();
    }

    private boolean rejected(String comment) {
        return moderation.moderate(comment).rejected();
    }

    @Nested
    @DisplayName("Nhóm A — Auto PASS (qua bước tự động → PENDING)")
    class Pass {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("TC-A2/A3: comment rỗng/trắng → cho qua")
        void blankPasses(String comment) {
            assertFalse(rejected(comment));
        }

        @Test
        @DisplayName("TC-A1: comment sạch → cho qua")
        void cleanPasses() {
            assertFalse(rejected("Sân đẹp, cỏ tốt, nhân viên thân thiện"));
        }

        @Test
        @DisplayName("TC-A4: số ngắn (<9 chữ số) không bị nhầm SĐT")
        void shortDigitsPass() {
            assertFalse(rejected("Mua 2 đôi size 39 và 40"));
        }
    }

    @Nested
    @DisplayName("Nhóm B — Auto REJECT (heuristic)")
    class Heuristics {

        @ParameterizedTest
        @ValueSource(strings = {
                "Hàng rẻ hơn ở https://shopgiay.vn",   // TC-B1 http
                "Ghé www.giaysi để xem",               // TC-B2 www.
                "order tại nikeoutlet.shop nhé"        // TC-B3 domain
        })
        @DisplayName("TC-B1..B3: chặn liên kết/quảng cáo")
        void rejectsLinks(String comment) {
            assertTrue(rejected(comment));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Liên hệ 0901234567 giá tốt",  // TC-B4
                "Call 0901.234.567"            // TC-B5 xen dấu chấm
        })
        @DisplayName("TC-B4/B5: chặn số điện thoại (≥9 số)")
        void rejectsPhone(String comment) {
            assertTrue(rejected(comment));
        }

        @Test
        @DisplayName("TC-B6/B7: chặn ký tự lặp ≥6 (spam)")
        void rejectsRepeatSpam() {
            assertTrue(rejected("Tuyệt vời !!!!!!!"));
            assertTrue(rejected("đẹppppppp lắm"));
        }

        @Test
        @DisplayName("TC-B8: lặp đúng 5 lần KHÔNG chặn")
        void repeatFivePasses() {
            assertFalse(rejected("Đẹp!!!!!"));
        }

        @Test
        @DisplayName("TC-B9: heuristic chạy trước từ cấm (link vẫn bị chặn dù có từ bẩn)")
        void linkBeforeBanned() {
            assertTrue(rejected("vcl xem shopabc.com"));
        }
    }

    @Nested
    @DisplayName("Nhóm C — Auto REJECT (từ cấm token đơn)")
    class BannedSingle {

        @Test
        @DisplayName("TC-C1: từ viết tắt tục token đơn")
        void singleToken() {
            assertTrue(rejected("vcl sân gì mà tệ"));
        }

        @Test
        @DisplayName("TC-C2: tiếng Anh tục")
        void english() {
            assertTrue(rejected("this is total shit"));
        }

        @Test
        @DisplayName("TC-C4: né bằng lặp ký tự — normalize gộp vvccll → vcl")
        void evasionByRepeat() {
            assertTrue(rejected("vvccll"));
        }

        @Test
        @DisplayName("TC-C6: token đơn nằm trong từ dài hơn KHÔNG chặn")
        void substringNotMatched() {
            assertFalse(rejected("vclothing rất đẹp"));
        }
    }

    @Nested
    @DisplayName("Nhóm D — Chống false-positive (phải PASS)")
    class FalsePositiveGuard {

        @ParameterizedTest
        @ValueSource(strings = {
                "Mình lấy cái lớn hơn vừa chân",  // TC-D1 "cái lớn"
                "Tôi ngủ rất ngon, đồ dùng ổn",   // TC-A5 ngu/do
                "Các bạn nên mua, đáng tiền",     // TC-A6 các → cac
                "Đỗ Nguyên góp ý rất nhiệt tình", // guard n-gram: "do nguyen" ⊅ "do ngu"
                "Đơn 12345678 giao nhanh"         // TC-D3 8 chữ số
        })
        @DisplayName("Câu tiếng Việt bình thường không bị chặn nhầm")
        void cleanVietnamesePasses(String comment) {
            assertFalse(rejected(comment));
        }
    }

    @Nested
    @DisplayName("Nhóm H — Cụm nhiều từ có dấu cách (n-gram, bug đã sửa)")
    class BannedPhrase {

        @ParameterizedTest
        @ValueSource(strings = {
                "đồ ngu",                  // TC-H1
                "con chó",                 // TC-H2
                "shop lừa đảo, hàng giả",  // TC-H3
                "vãi lồn"                  // TC-H4
        })
        @DisplayName("TC-H1..H4: cụm tục viết có dấu cách bị chặn")
        void spacedPhraseRejected(String comment) {
            assertTrue(rejected(comment));
        }

        @Test
        @DisplayName("Cụm vẫn bắt khi né bằng bỏ dấu + lặp ký tự: 'đồ  nguuu'")
        void phraseEvasion() {
            assertTrue(rejected("đồ  nguuu"));
        }
    }
}
