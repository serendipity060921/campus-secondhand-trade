package com.campus.trade.common.util;

import java.util.Locale;
import java.util.Map;

/**
 * 图片类型识别与校验（v0.18 / 第2批-⑤）。
 *
 * <p><b>为什么需要它</b>：上传校验如果只看扩展名与 Content-Type，等于信任客户端的自述 ——
 * 这两者都可以随意伪造。一个名为 <code>a.png</code>、标着 <code>image/png</code> 的文件，
 * 内容完全可以是 HTML、PHP 或可执行文件。真正可靠的判据是文件**真实内容的头几个字节**
 * （magic number / 文件签名）。</p>
 *
 * <p>本类只做一件纯函数式的事：给一段字节头，判断它是哪种图片；以及该类型与扩展名是否自洽。
 * 不做 IO、不依赖 Spring，因此可以被单元测试完整覆盖。</p>
 *
 * <p>支持的格式与签名：</p>
 * <table border="1">
 *   <caption>文件签名</caption>
 *   <tr><th>格式</th><th>扩展名</th><th>签名（十六进制）</th></tr>
 *   <tr><td>JPEG</td><td>jpg / jpeg</td><td><code>FF D8 FF</code></td></tr>
 *   <tr><td>PNG</td><td>png</td><td><code>89 50 4E 47 0D 0A 1A 0A</code></td></tr>
 *   <tr><td>GIF</td><td>gif</td><td><code>47 49 46 38</code>（"GIF8"）</td></tr>
 *   <tr><td>BMP</td><td>bmp</td><td><code>42 4D</code>（"BM"）</td></tr>
 *   <tr><td>WEBP</td><td>webp</td><td><code>52 49 46 46</code>（"RIFF"）+ 第 8~11 字节 <code>WEBP</code></td></tr>
 * </table>
 */
public final class ImageTypeValidator {

    /** 读取多少字节足够判断：WEBP 需要看到第 12 个字节，这里留足余量 */
    public static final int HEAD_BYTES = 16;

    public static final String JPEG = "jpeg";
    public static final String PNG = "png";
    public static final String GIF = "gif";
    public static final String BMP = "bmp";
    public static final String WEBP = "webp";

    /** 扩展名 → 规范类型名（一个类型可以对应多个扩展名，例如 jpg / jpeg） */
    private static final Map<String, String> EXT_TO_TYPE = Map.of(
            "jpg", JPEG,
            "jpeg", JPEG,
            "png", PNG,
            "gif", GIF,
            "bmp", BMP,
            "webp", WEBP);

    private ImageTypeValidator() {
    }

    /**
     * 根据文件头判断图片类型。
     *
     * @param head 文件开头的若干字节（建议 {@link #HEAD_BYTES} 个，少一些也能识别 JPEG/PNG/GIF/BMP）
     * @return 规范类型名（jpeg / png / gif / bmp / webp）；无法识别时返回 {@code null}
     */
    public static String detect(byte[] head) {
        if (head == null || head.length < 2) {
            return null;
        }
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (startsWith(head, 0x47, 0x49, 0x46, 0x38)) {
            return GIF;
        }
        if (startsWith(head, 0x42, 0x4D)) {
            return BMP;
        }
        // WEBP：RIFF????WEBP —— 前 4 字节 RIFF，第 8~11 字节 WEBP
        if (startsWith(head, 0x52, 0x49, 0x46, 0x46) && head.length >= 12
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return WEBP;
        }
        return null;
    }

    /**
     * 判断"识别出的真实类型"与"文件名扩展名"是否自洽。
     *
     * @param detectedType {@link #detect} 的返回值（可为 null）
     * @param extension    文件名扩展名（大小写不敏感，不带点）
     * @return 两者一致返回 true
     */
    public static boolean matchesExtension(String detectedType, String extension) {
        if (detectedType == null || extension == null) {
            return false;
        }
        String expect = EXT_TO_TYPE.get(extension.toLowerCase(Locale.ROOT));
        return detectedType.equals(expect);
    }

    /** 扩展名是否在支持范围内（与 {@link #EXT_TO_TYPE} 保持一致） */
    public static boolean supportsExtension(String extension) {
        return extension != null && EXT_TO_TYPE.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
