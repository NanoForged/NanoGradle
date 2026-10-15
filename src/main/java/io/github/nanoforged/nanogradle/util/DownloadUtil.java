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
import java.util.Comparator;
import java.util.UUID;

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

    /**
     * SevenZip 原生库临时解压目录的前缀，目录名形如 {@code nanogradle-sevenzip-<pid>-<token>}。
     */
    private static final String SEVENZIP_TMP_PREFIX = "nanogradle-sevenzip-";

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
        ensureSevenZipInitialized(logger);
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
     * 初始化 SevenZip 原生库（从 all-platforms jar 中按平台加载，每个 classloader 代仅执行一次）。
     * <p>
     * <b>每次初始化都必须使用一个全新的临时目录</b>，原因有两条：
     * <ol>
     *   <li>SevenZipJBinding 16.02-2.01 的“已解压则跳过”校验失效：其
     *       {@code SevenZip.byteArrayToHex} 不补零，算出的 SHA1 十六进制串比 properties 中的
     *       期望值短，比较<b>永不相等</b>，所以它在文件已存在时依然会重写原生库；</li>
     *   <li>Gradle 守护进程存活期间插件 classloader 会被重建，新一代 {@code SevenZip} 的静态
     *       状态归零后会再次初始化。此时若沿用同一路径，就会覆盖本进程已映射加载、在 Windows
     *       上不可写入的 DLL，抛出 {@code can't copy native library out of a resource file to
     *       the temporary location} 并令解压任务失败。</li>
     * </ol>
     * 复用已被本进程加载的 DLL 也不可行：同一路径无法在第二个 classloader 中重新
     * {@code System.load}（JVM 直接抛 {@code UnsatisfiedLinkError: already loaded in
     * another classloader}），所以独立目录是唯一可行的方案。
     *
     * @param logger Gradle 日志
     * @throws GradleException 原生库加载或解压失败时抛出
     */
    private static void ensureSevenZipInitialized(Logger logger) {
        if (sevenZipReady) {
            return;
        }
        synchronized (DownloadUtil.class) {
            if (sevenZipReady) {
                return;
            }
            Path libDir = null;
            try {
                cleanupStaleLibDirs(logger);
                String token = UUID.randomUUID().toString().substring(0, 8);
                libDir = Path.of(System.getProperty("java.io.tmpdir"),
                        SEVENZIP_TMP_PREFIX + ProcessHandle.current().pid() + "-" + token);
                Files.createDirectories(libDir);
                SevenZip.initSevenZipFromPlatformJAR(libDir.toFile());
                sevenZipReady = true;
            } catch (SevenZipNativeInitializationException | IOException e) {
                throw new GradleException("nanogradle: SevenZip 原生库加载失败，请检查 sevenzipjbinding-all-platforms 依赖", e);
            } catch (RuntimeException e) {
                throw new GradleException("nanogradle: SevenZip 原生库解压失败，临时目录 " + libDir
                        + "（可删除 java.io.tmpdir 下的 " + SEVENZIP_TMP_PREFIX + "* 后重试）", e);
            }
        }
    }

    /**
     * 清理其他已退出进程残留在临时目录下的 SevenZip 解压目录。
     * <p>
     * 原生库文件在本进程存活期间无法删除（Windows 上被映射占用），因此只能在每次初始化前
     * 顺手回收死进程的目录，避免 {@code java.io.tmpdir} 按守护进程数累积约 5 MB 的 DLL。
     * 任何清理失败都不影响初始化，仅记录日志。
     *
     * @param logger Gradle 日志
     */
    private static void cleanupStaleLibDirs(Logger logger) {
        Path tmpDir;
        try {
            tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        } catch (RuntimeException e) {
            return;
        }
        if (!Files.isDirectory(tmpDir)) {
            return;
        }
        long selfPid = ProcessHandle.current().pid();
        try (var entries = Files.list(tmpDir)) {
            for (Path dir : entries.toList()) {
                String name = dir.getFileName().toString();
                if (!name.startsWith(SEVENZIP_TMP_PREFIX) || !Files.isDirectory(dir)) {
                    continue;
                }
                long pid = parsePid(name);
                // pid 无法识别、是自己、或该进程仍然活着（可能是并发的另一次构建）时一律跳过
                if (pid < 0 || pid == selfPid || ProcessHandle.of(pid).isPresent()) {
                    continue;
                }
                if (deleteRecursively(dir)) {
                    logger.info("nanogradle: 已清理残留的 SevenZip 临时目录 {}", dir);
                }
            }
        } catch (IOException e) {
            logger.debug("nanogradle: 清理 SevenZip 临时目录失败（已忽略）：{}", e.toString());
        }
    }

    /**
     * 从 SevenZip 临时目录名中解析出所属进程号。
     *
     * @param name 目录名，形如 {@code nanogradle-sevenzip-<pid>[-<token>]}
     * @return 进程号，格式无法识别时返回 {@code -1}
     */
    private static long parsePid(String name) {
        String rest = name.substring(SEVENZIP_TMP_PREFIX.length());
        int dash = rest.indexOf('-');
        if (dash > 0) {
            rest = rest.substring(0, dash);
        }
        try {
            return Long.parseLong(rest);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 递归删除目录及其内容，失败时返回 {@code false} 而不抛出。
     * <p>
     * 与任务层的删除不同，这里删除的是可再生的临时残留，失败（例如仍被占用）不是错误。
     *
     * @param dir 目标目录
     * @return 目录已不存在时返回 {@code true}
     */
    private static boolean deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
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
