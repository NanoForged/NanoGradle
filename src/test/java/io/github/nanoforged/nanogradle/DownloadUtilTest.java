package io.github.nanoforged.nanogradle;

import io.github.nanoforged.nanogradle.util.DownloadUtil;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DownloadUtil} 单元测试：URL 占位符替换与归档解压。
 */
class DownloadUtilTest {

    @TempDir
    Path tempDir;

    /**
     * 验证 {version} 占位符被替换。
     */
    @Test
    void buildUrlReplacesVersionPlaceholder() {
        assertEquals(
                "https://example.com/nsis-0.97a.zip",
                DownloadUtil.buildUrl("https://example.com/nsis-{version}.zip", "0.97a"));
        assertEquals(
                "https://example.com/a/0.97a/b/0.97a",
                DownloadUtil.buildUrl("https://example.com/a/{version}/b/{version}", "0.97a"));
    }

    /**
     * 验证缺失版本号/URL 时抛出引导错误。
     */
    @Test
    void buildUrlRejectsMissingInput() {
        assertThrows(org.gradle.api.GradleException.class,
                () -> DownloadUtil.buildUrl("   ", "0.97a"));
        assertThrows(org.gradle.api.GradleException.class,
                () -> DownloadUtil.buildUrl("https://example.com/nsis-{version}.zip", " "));
        assertThrows(org.gradle.api.GradleException.class,
                () -> DownloadUtil.buildUrl(null, null));
    }

    /**
     * 验证普通 zip 可完整解压，含嵌套目录。
     */
    @Test
    void extractZipArchive() throws Exception {
        Path archive = tempDir.resolve("fixture.zip");
        Files.write(archive, zip(
                entry("starfarer.api.jar", "api-bytes"),
                entry("data/campaign/factions.csv", "factions"),
                entry("graphics/logo.png", "png-bytes")));

        Path out = tempDir.resolve("out");
        DownloadUtil.extractArchive(archive, out, Logging.getLogger("test"));

        assertTrue(Files.isRegularFile(out.resolve("starfarer.api.jar")));
        assertEquals("api-bytes", Files.readString(out.resolve("starfarer.api.jar")));
        assertTrue(Files.isRegularFile(out.resolve("data/campaign/factions.csv")));
        assertTrue(Files.isRegularFile(out.resolve("graphics/logo.png")));
    }

    /**
     * 验证路径穿越条目（.. 或反斜杠变体）被拒绝，不落盘到输出目录外。
     */
    @Test
    void extractRejectsPathTraversal() throws Exception {
        Path archive = tempDir.resolve("evil.zip");
        Files.write(archive, zip(entry("../evil.txt", "evil")));

        Path out = tempDir.resolve("out");
        assertThrows(IOException.class, () -> DownloadUtil.extractArchive(archive, out, Logging.getLogger("test")));
        assertFalse(Files.exists(out.getParent().resolve("evil.txt")));

        Path archive2 = tempDir.resolve("evil2.zip");
        Files.write(archive2, zip(entry("..\\evil.txt", "evil")));
        Path out2 = tempDir.resolve("out2");
        assertThrows(IOException.class, () -> DownloadUtil.extractArchive(archive2, out2, Logging.getLogger("test")));
    }

    /**
     * 生成单条目 zip 字节流（为可读性用文本内容）。
     *
     * @param entries 条目数据
     * @return zip 字节
     */
    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Entry entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.name));
                zos.write(entry.content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private static final class Entry {
        private final String name;
        private final String content;

        private Entry(String name, String content) {
            this.name = name;
            this.content = content;
        }
    }
}
