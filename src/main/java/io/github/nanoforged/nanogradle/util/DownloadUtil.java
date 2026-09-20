package io.github.nanoforged.nanogradle.util;

import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipNativeInitializationException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.simple.ISimpleInArchive;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 下载与解压工具类。
 * <p>
 * 提供三类能力：
 * <ul>
 *   <li>{@link #buildUrl} - 将 URL 模板中的 {@code {version}} 占位符替换为真实版本号；</li>
 *   <li>{@link #download} - 基于 OkHttp 的下载（含磁盘缓存与 304 复用，见设计文档 §3.1/§5.1）；</li>
 *   <li>{@link #extractArchive} - 基于 SevenZipJBinding 的归档解压（见设计文档 §3.2）。</li>
 * </ul>
 */
public final class DownloadUtil {

    private static final long OKHTTP_CACHE_MAX_BYTES = 512L * 1024 * 1024; // 512 MiB
    private static final String USER_AGENT = "NanoGradle/1.0";

    private static volatile boolean sevenZipReady;

    private DownloadUtil() {
    }

    /**
     * 将 URL 模板中的 {@code {version}} 占位符替换为真实版本号。
     *
     * @param urlTemplate 下载 URL 模板，如 {@code https://example.com/nsis-{version}.zip}
     * @param version     版本号，如 {@code 0.97a}
     * @return 替换后的完整下载地址
     * @throws GradleException 模板或版本号为空时抛出
     */
    public static String buildUrl(String urlTemplate, String version) {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            throw new GradleException("nanogradle: 未配置 url，请在 nanogradle {} 中设置下载地址（可含 {version} 占位符）");
        }
        if (version == null || version.isBlank()) {
            throw new GradleException("nanogradle: 未配置 version，请在 nanogradle {} 中设置游戏版本号（如 0.97a）");
        }
        return urlTemplate.replace("{version}", version);
    }

    /**
     * 创建带磁盘缓存的 OkHttp 客户端。
     * <p>
     * 缓存目录为 {@code <gradle user home>/caches/okhttp}，可跨 Gradle 构建与 clean 复用；
     * 服务端返回 304 时直接复用缓存体（见设计文档 §3.1）。
     *
     * @param gradleUserHomeDir Gradle 用户主目录
     * @return 共享配置的 OkHttpClient
     */
    public static OkHttpClient newHttpClient(File gradleUserHomeDir) {
        File cacheDir = new File(gradleUserHomeDir, "caches/okhttp");
        Cache cache = new Cache(cacheDir, OKHTTP_CACHE_MAX_BYTES);
        return new OkHttpClient.Builder()
                .cache(cache)
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(5))
                .followRedirects(true)
                .build();
    }

    /**
     * 下载 URL 内容到目标文件。
     * <p>
     * 先写入同目录临时文件再原子移动，避免中断留下残缺文件；OkHttp 磁盘缓存命中时
     * （304 或新鲜缓存）body 直接来自缓存，本方法无需区分来源。
     *
     * @param url    完整下载地址
     * @param target 目标文件路径
     * @param client OkHttp 客户端
     * @param logger Gradle 日志
     * @throws IOException 网络或 IO 异常
     */
    public static void download(String url, Path target, OkHttpClient client, Logger logger) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        logger.lifecycle("nanogradle: 下载 {}", url);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("nanogradle: 下载失败 HTTP " + response.code() + ": " + response.message() + " <- " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("nanogradle: 下载响应体为空（可能服务端返回 304 但本地无缓存）: " + url);
            }

            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            try (var in = body.byteStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.lifecycle("nanogradle: 下载完成 {} ({} bytes)", target.getFileName(), Files.size(target));
        }
    }

    /**
     * 使用 SevenZipJBinding 将归档（zip / NSIS 安装器等）解压到输出目录。
     * <p>
     * 仅解压普通文件项；每个文件在解压期间保持单一输出流连续写入
     * （见设计文档 §6.2.d），并对路径做了穿越目录防护。
     *
     * @param archive   归档文件
     * @param outputDir 解压输出目录（须为空或不存在，任务层负责清空）
     * @param logger    Gradle 日志
     * @throws Exception 解压过程中的任意异常
     */
    public static void extractArchive(Path archive, Path outputDir, Logger logger) throws Exception {
        ensureSevenZipInitialized();
        Files.createDirectories(outputDir);
        logger.lifecycle("nanogradle: 解压 {} -> {}", archive.getFileName(), outputDir);

        try (RandomAccessFile raf = new RandomAccessFile(archive.toFile(), "r");
             IInArchive inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf))) {

            ISimpleInArchive simple = inArchive.getSimpleInterface();
            int extracted = 0;
            for (ISimpleInArchiveItem item : simple.getArchiveItems()) {
                if (item.isFolder()) {
                    continue;
                }
                Path target = safeResolve(outputDir, item.getPath());
                Files.createDirectories(target.getParent());

                ExtractOperationResult result;
                try (OutputStream out = Files.newOutputStream(target)) {
                    result = item.extractSlow(data -> {
                        try {
                            out.write(data);
                            return data.length;
                        } catch (IOException e) {
                            throw new RuntimeException("nanogradle: 写入解压文件失败: " + target, e);
                        }
                    });
                }
                if (result != ExtractOperationResult.OK) {
                    Files.deleteIfExists(target);
                    logger.warn("nanogradle: 解压 {} 失败: {}", item.getPath(), result);
                } else {
                    extracted++;
                }
            }
            logger.lifecycle("nanogradle: 解压完成，共 {} 个文件", extracted);
        }
    }

    /**
     * 初始化 SevenZip 原生库（从 all-platforms jar 中按平台加载，仅执行一次）。
     * <p>
     * 使用进程号（PID）唯一的临时子目录，避免多个 Gradle 守护进程同时初始化时
     * 对同一原生库文件的写冲突。
     *
     * @throws GradleException 原生库加载失败时抛出
     */
    private static void ensureSevenZipInitialized() {
        if (sevenZipReady) {
            return;
        }
        synchronized (DownloadUtil.class) {
            if (sevenZipReady) {
                return;
            }
            try {
                long pid = ProcessHandle.current().pid();
                Path libDir = Path.of(System.getProperty("java.io.tmpdir"), "nanogradle-sevenzip-" + pid);
                Files.createDirectories(libDir);
                SevenZip.initSevenZipFromPlatformJAR(libDir.toFile());
                sevenZipReady = true;
            } catch (SevenZipNativeInitializationException | IOException e) {
                throw new GradleException("nanogradle: SevenZip 原生库加载失败，请检查 sevenzipjbinding-all-platforms 依赖", e);
            }
        }
    }

    /**
     * 将归档内相对路径安全地解析到输出目录内，阻止路径穿越。
     *
     * @param outputDir 输出根目录
     * @param entryPath 归档项路径（可能使用 {@code \} 分隔）
     * @return 安全的目标路径
     * @throws IOException 路径非法时抛出
     */
    private static Path safeResolve(Path outputDir, String entryPath) throws IOException {
        String normalized = entryPath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path root = outputDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("nanogradle: 归档包含非法路径，已拒绝: " + entryPath);
        }
        return resolved;
    }
}
