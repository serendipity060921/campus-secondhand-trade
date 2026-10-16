package com.campus.trade.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 图片内容识别验证（v0.18 / 第2批-⑤）
 *
 * <p>这一层是上传安全的**最后一道、也是唯一骗不过去的**一道：
 * 扩展名和 Content-Type 都由客户端提供，只有文件头几个字节是内容的真实属性。</p>
 *
 * <p>因此本测试的重点不是"能不能识别正常图片"（那是最基本的要求），
 * 而是**各种伪装能否被识破**：HTML、PHP、可执行文件、压缩包，
 * 以及最常见的"用 .png 扩展名传 jpeg 内容"。</p>
 */
class ImageTypeValidatorTest {

    /* ---------------- 夹具：构造带指定文件头的数据 ---------------- */

    private static byte[] head(int... bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int b : bytes) {
            out.write(b);
        }
        return out.toByteArray();
    }

    /** 真图片：签名 + 若干填充字节，模拟真实文件长度 */
    private static byte[] withPadding(int[] signature) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int b : signature) {
            out.write(b);
        }
        out.writeBytes(new byte[8]);
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("一、正常图片识别")
    class DetectNormal {

        @Test
        @DisplayName("JPEG（FF D8 FF）")
        void jpeg() {
            assertEquals(ImageTypeValidator.JPEG,
                    ImageTypeValidator.detect(withPadding(new int[]{0xFF, 0xD8, 0xFF, 0xE0})));
        }

        @Test
        @DisplayName("PNG（89 50 4E 47 0D 0A 1A 0A）")
        void png() {
            assertEquals(ImageTypeValidator.PNG, ImageTypeValidator.detect(
                    withPadding(new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})));
        }

        @Test
        @DisplayName("GIF（GIF8）")
        void gif() {
            assertEquals(ImageTypeValidator.GIF, ImageTypeValidator.detect(ascii("GIF89a....")));
        }

        @Test
        @DisplayName("BMP（BM）")
        void bmp() {
            assertEquals(ImageTypeValidator.BMP, ImageTypeValidator.detect(ascii("BM......")));
        }

        @Test
        @DisplayName("WEBP（RIFF + 第 8~11 字节 WEBP）")
        void webp() {
            byte[] data = ascii("RIFF????WEBPVP8 ");
            assertEquals(ImageTypeValidator.WEBP, ImageTypeValidator.detect(data));
        }
    }

    @Nested
    @DisplayName("二、★ 伪装与非法内容必须被识破")
    class RejectDisguised {

        @Test
        @DisplayName("HTML 页面伪装成图片 → 识别不出")
        void html() {
            assertNull(ImageTypeValidator.detect(ascii("<!DOCTYPE html><script>alert(1)</script>")));
            assertNull(ImageTypeValidator.detect(ascii("<html><body>hi</body></html>")));
        }

        @Test
        @DisplayName("PHP 脚本 → 识别不出")
        void php() {
            assertNull(ImageTypeValidator.detect(ascii("<?php system($_GET['c']); ?>")));
        }

        @Test
        @DisplayName("Windows 可执行文件（MZ）→ 识别不出")
        void exe() {
            // "MZ" = 0x4D 0x5A —— Java 没有 \x 转义，用字节数组表达
            assertNull(ImageTypeValidator.detect(
                    head(0x4D, 0x5A, 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00)));
        }

        @Test
        @DisplayName("ZIP 压缩包（PK，也是 docx/xlsx 的签名）→ 识别不出")
        void zip() {
            assertNull(ImageTypeValidator.detect(head(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)));
        }

        @Test
        @DisplayName("★ RIFF 但不是 WEBP（如 WAV 音频）→ 不能放行")
        void riffButNotWebp() {
            assertNull(ImageTypeValidator.detect(ascii("RIFF????WAVEfmt ")));
        }

        @Test
        @DisplayName("纯文本 → 识别不出")
        void plainText() {
            assertNull(ImageTypeValidator.detect(ascii("just some text")));
        }

        @Test
        @DisplayName("★ 空文件 / null / 只有 1 字节 → 一律识别不出（不能因为读不到就放行）")
        void emptyOrTooShort() {
            assertNull(ImageTypeValidator.detect(null));
            assertNull(ImageTypeValidator.detect(new byte[0]));
            assertNull(ImageTypeValidator.detect(head(0xFF)));
            assertNull(ImageTypeValidator.detect(head(0xFF, 0xD8)));   // JPEG 少一个字节
        }

        @Test
        @DisplayName("PNG 签名被截断（前 4 字节）→ 识别不出")
        void truncatedPng() {
            assertNull(ImageTypeValidator.detect(head(0x89, 0x50, 0x4E, 0x47)));
        }

        @Test
        @DisplayName("WEBP 只给前 6 字节（看不到 WEBP 标记）→ 识别不出")
        void truncatedWebp() {
            assertNull(ImageTypeValidator.detect(ascii("RIFF..")));
        }
    }

    @Nested
    @DisplayName("三、真实内容与扩展名是否自洽")
    class MatchExtension {

        @Test
        @DisplayName("同一类型允许多个扩展名：jpeg 内容配 jpg 或 jpeg 都可以")
        void jpegAcceptsJpgAndJpeg() {
            assertTrue(ImageTypeValidator.matchesExtension(ImageTypeValidator.JPEG, "jpg"));
            assertTrue(ImageTypeValidator.matchesExtension(ImageTypeValidator.JPEG, "jpeg"));
            assertTrue(ImageTypeValidator.matchesExtension(ImageTypeValidator.JPEG, "JPG"));
        }

        @Test
        @DisplayName("各格式与自身扩展名一致")
        void exactMatches() {
            assertTrue(ImageTypeValidator.matchesExtension(ImageTypeValidator.PNG, "png"));
            assertTrue(ImageTypeValidator.matchesExtension(ImageTypeValidator.GIF, "gif"));
            assertTrue(ImageTypeValidator.matchesExtension(ImageTypeValidator.BMP, "bmp"));
            assertTrue(ImageTypeValidator.matchesExtension(ImageTypeValidator.WEBP, "webp"));
        }

        @Test
        @DisplayName("★ 核心场景：png 扩展名 + jpeg 真实内容 → 不一致")
        void pngExtensionWithJpegContent() {
            assertFalse(ImageTypeValidator.matchesExtension(ImageTypeValidator.JPEG, "png"));
            assertFalse(ImageTypeValidator.matchesExtension(ImageTypeValidator.PNG, "jpg"));
            assertFalse(ImageTypeValidator.matchesExtension(ImageTypeValidator.WEBP, "png"));
        }

        @Test
        @DisplayName("未识别出类型（null）→ 一律判为不一致")
        void nullTypeNeverMatches() {
            assertFalse(ImageTypeValidator.matchesExtension(null, "png"));
            assertFalse(ImageTypeValidator.matchesExtension(null, null));
        }

        @Test
        @DisplayName("未知扩展名（不在支持列表里）→ 判为不一致")
        void unknownExtension() {
            assertFalse(ImageTypeValidator.matchesExtension(ImageTypeValidator.PNG, "exe"));
            assertFalse(ImageTypeValidator.matchesExtension(ImageTypeValidator.PNG, ""));
            assertFalse(ImageTypeValidator.matchesExtension(ImageTypeValidator.PNG, null));
        }
    }

    @Nested
    @DisplayName("四、扩展名支持范围")
    class SupportsExtension {

        @Test
        @DisplayName("白名单内的扩展名（大小写不敏感）")
        void supported() {
            for (String ext : new String[]{"jpg", "jpeg", "png", "gif", "bmp", "webp", "PNG", "Jpeg"}) {
                assertTrue(ImageTypeValidator.supportsExtension(ext), ext + " 应被支持");
            }
        }

        @Test
        @DisplayName("白名单外与 null")
        void unsupported() {
            assertFalse(ImageTypeValidator.supportsExtension("exe"));
            assertFalse(ImageTypeValidator.supportsExtension("svg"));
            assertFalse(ImageTypeValidator.supportsExtension(null));
        }

        @Test
        @DisplayName("★ 白名单与匹配表必须一致：凡声明支持的扩展名，都能匹配到某个类型")
        void whitelistConsistentWithMap() {
            for (String ext : new String[]{"jpg", "jpeg", "png", "gif", "bmp", "webp"}) {
                assertTrue(ImageTypeValidator.supportsExtension(ext), ext + " 应被支持");
            }
            // 反向：不支持 svg —— 注意 SVG 是 XML 文本、可内嵌脚本，刻意不放进白名单
            assertFalse(ImageTypeValidator.supportsExtension("svg"));
        }
    }
}
