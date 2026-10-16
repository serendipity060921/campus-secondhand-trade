package com.campus.trade.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.campus.trade.common.exception.ProductException;
import com.campus.trade.config.FileUploadProperties;
import com.campus.trade.vo.FileUploadVO;

/**
 * 文件服务实现验证（v0.18 / 第3批-⑫）
 *
 * <p>上传是**唯一一条把外部字节写进服务器磁盘**的路径，它的每一道校验都必须有测试：
 * 扩展名、Content-Type、★真实内容（magic number）、大小，任何一道漏了都可能被当成"网盘"用，
 * 甚至写入可执行文件。</p>
 *
 * <p>存储路径与文件名也一并固定：<code>{根目录}/yyyy/MM/{uuid}.{ext}</code> ——
 * 用 UUID 而不是原始文件名，才能避免 <code>../../etc/passwd</code> 这类路径穿越。</p>
 */
class FileServiceImplTest {

    @TempDir
    Path tempDir;

    private FileUploadProperties properties;
    private FileServiceImpl fileService;

    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    @BeforeEach
    void setUp() throws IOException {
        properties = new FileUploadProperties();
        properties.setPath(tempDir.toString());
        properties.setUrlPrefix("/upload");
        properties.setMaxSize(5 * 1024 * 1024L);
        properties.setMaxCount(9);
        properties.setAllowedExtensions(List.of("jpg", "jpeg", "png", "gif", "webp", "bmp"));

        fileService = new FileServiceImpl(properties);
        fileService.init();
    }

    private MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    @Nested
    @DisplayName("一、初始化与基础信息")
    class Init {

        @Test
        @DisplayName("初始化会创建上传根目录，getBasePath 返回绝对路径")
        void initCreatesDirectory() {
            assertTrue(Files.isDirectory(tempDir));
            assertNotNull(fileService.getBasePath());
            assertTrue(fileService.getBasePath().startsWith(tempDir.toAbsolutePath().toString()));
        }
    }

    @Nested
    @DisplayName("二、四道校验：非空 / 扩展名 / Content-Type / ★真实内容 / 大小")
    class Validation {

        @Test
        @DisplayName("空文件 → 文件为空")
        void emptyFile() {
            ProductException e = assertThrows(ProductException.class,
                    () -> fileService.store(file("a.png", "image/png", new byte[0])));
            assertEquals(ProductException.fileEmpty().getCode(), e.getCode());
        }

        @Test
        @DisplayName("null → 文件为空（不抛 NPE）")
        void nullFile() {
            assertThrows(ProductException.class, () -> fileService.store(null));
        }

        @Test
        @DisplayName("扩展名不在白名单 → 拒绝（.exe / .php / .jsp）")
        void badExtension() {
            for (String name : new String[]{"a.exe", "b.php", "c.jsp", "d.svg"}) {
                ProductException e = assertThrows(ProductException.class,
                        () -> fileService.store(file(name, "image/png", PNG)), "文件名：" + name);
                assertEquals(ProductException.fileTypeNotAllowed("x").getCode(), e.getCode());
            }
        }

        @Test
        @DisplayName("没有扩展名 → 拒绝")
        void noExtension() {
            assertThrows(ProductException.class,
                    () -> fileService.store(file("noext", "image/png", PNG)));
        }

        @Test
        @DisplayName("Content-Type 不是 image/* → 拒绝（即使扩展名对）")
        void badContentType() {
            assertThrows(ProductException.class,
                    () -> fileService.store(file("a.png", "text/html", PNG)));
            assertThrows(ProductException.class,
                    () -> fileService.store(file("a.png", null, PNG)));
        }

        @Test
        @DisplayName("★ 内容是 HTML 却叫 .png → 被 magic number 拦下")
        void htmlDisguisedAsPng() {
            byte[] html = "<!DOCTYPE html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

            ProductException e = assertThrows(ProductException.class,
                    () -> fileService.store(file("fake.png", "image/png", html)));
            assertEquals(ProductException.fileTypeNotAllowed("x").getCode(), e.getCode());
            assertTrue(e.getMessage().contains("不是有效图片"), "实际提示：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 内容是 JPEG 却叫 .png → 拒绝并说明不一致")
        void mismatchExtensionAndContent() {
            ProductException e = assertThrows(ProductException.class,
                    () -> fileService.store(file("mismatch.png", "image/png", JPEG)));
            assertTrue(e.getMessage().contains("不一致"), "实际提示：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 超过大小上限 → 拒绝")
        void tooLarge() {
            properties.setMaxSize(10);
            assertThrows(ProductException.class,
                    () -> fileService.store(file("big.png", "image/png", PNG)));
        }

        @Test
        @DisplayName("校验失败时不会在磁盘上留下任何文件")
        void nothingWrittenOnFailure() throws IOException {
            try {
                fileService.store(file("fake.png", "image/png",
                        "not an image".getBytes(StandardCharsets.UTF_8)));
            } catch (ProductException ignored) {
                // 预期
            }
            try (var walk = Files.walk(tempDir)) {
                long files = walk.filter(Files::isRegularFile).count();
                assertEquals(0, files, "校验失败不应写入文件");
            }
        }
    }

    @Nested
    @DisplayName("三、成功路径：安全的文件名与可访问地址")
    class Store {

        @Test
        @DisplayName("★ 存成 {根}/yyyy/MM/{uuid}.png，且返回可访问 URL")
        void storePng() throws IOException {
            FileUploadVO vo = fileService.store(file("我的照片.PNG", "image/png", PNG));

            assertNotNull(vo.getUrl());
            assertTrue(vo.getUrl().startsWith("/upload/"), "URL 前缀应来自配置，实际：" + vo.getUrl());
            assertTrue(vo.getUrl().endsWith(".png"), "扩展名应统一为小写，实际：" + vo.getUrl());
            assertEquals("我的照片.PNG", vo.getOriginalName(), "原始文件名仅用于展示");
            assertEquals(PNG.length, vo.getSize());

            // URL 去掉前缀后应能在磁盘上找到真实文件
            Path stored = tempDir.resolve(vo.getUrl().substring("/upload/".length()));
            assertTrue(Files.exists(stored), "文件应真实落盘：" + stored);

            // ★ 文件名必须是 UUID（与原始名无关）—— 这样才不会有路径穿越
            String fileName = stored.getFileName().toString();
            assertFalse(fileName.contains("我的照片"), "落盘文件名不应包含原始名");
            assertTrue(fileName.matches("[0-9a-f]{32}\\.png"), "应为 32 位 uuid 十六进制，实际：" + fileName);
        }

        @Test
        @DisplayName("★ 文件名里的路径穿越被清洗（cleanPath + UUID 双保险）")
        void pathTraversalInName() throws IOException {
            FileUploadVO vo = fileService.store(file("../../evil.png", "image/png", PNG));

            assertTrue(vo.getUrl().endsWith(".png"));
            Path stored = tempDir.resolve(vo.getUrl().substring("/upload/".length())).normalize();
            assertTrue(stored.startsWith(tempDir.toAbsolutePath().normalize()),
                    "落盘路径必须仍在上传根目录之内，实际：" + stored);
        }

        @Test
        @DisplayName("按日期分目录（yyyy/MM）")
        void dateDirectory() {
            FileUploadVO vo = fileService.store(file("a.png", "image/png", PNG));

            String[] parts = vo.getUrl().substring("/upload/".length()).split("/");
            assertEquals(3, parts.length, "应为 yyyy/MM/file 三段，实际：" + vo.getUrl());
            assertTrue(parts[0].matches("\\d{4}"), "年目录：" + parts[0]);
            assertTrue(parts[1].matches("\\d{2}"), "月目录：" + parts[1]);
        }

        @Test
        @DisplayName("jpeg 扩展名同样放行（同一类型的第二个扩展名）")
        void jpegExtension() {
            FileUploadVO vo = fileService.store(file("b.jpeg", "image/jpeg", JPEG));
            assertTrue(vo.getUrl().endsWith(".jpeg"));
        }

        @Test
        @DisplayName("多次上传得到不同文件名（不会互相覆盖）")
        void uniqueNames() {
            String u1 = fileService.store(file("a.png", "image/png", PNG)).getUrl();
            String u2 = fileService.store(file("a.png", "image/png", PNG)).getUrl();

            assertFalse(u1.equals(u2), "两次上传应生成不同文件名");
        }

        @Test
        @DisplayName("读取流失败 → 转成上传失败错误（不把 IOException 抛给上层）")
        void ioFailureWrapped() throws IOException {
            var broken = mock(org.springframework.web.multipart.MultipartFile.class);
            when(broken.isEmpty()).thenReturn(false);
            when(broken.getOriginalFilename()).thenReturn("a.png");
            when(broken.getContentType()).thenReturn("image/png");
            when(broken.getSize()).thenReturn(100L);
            when(broken.getInputStream()).thenThrow(new IOException("disk error"));

            ProductException e = assertThrows(ProductException.class, () -> fileService.store(broken));
            assertEquals(ProductException.uploadError("x").getCode(), e.getCode());
        }
    }
}
