package io.github.nanoforged.nanogradle;

import io.github.nanoforged.nanogradle.util.DownloadUtil;
import net.sf.sevenzipjbinding.SevenZip;
import okhttp3.OkHttpClient;
import org.gradle.api.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SevenZip 原生库在 Gradle 守护进程内<b>二次初始化</b>的回归测试。
 * <p>
 * 复现的缺陷：SevenZipJBinding 16.02-2.01 的 {@code byteArrayToHex} 不补零，导致其
 * “原生库已解压且哈希一致则跳过”的校验恒不成立，它永远重写原生库文件；而守护进程存活期间
 * 插件 classloader 会被重建，新一代 {@code SevenZip} 静态状态归零后会再次初始化，于是去覆盖
 * 本进程已映射加载、Windows 上不可写的 DLL，报
 * {@code can't copy native library out of a resource file to the temporary location}
 * 并让 {@code extractNsis} 失败。
 * <p>
 * 这里用两个只有父加载器不同（均为平台加载器）的 {@link URLClassLoader} 加载同一份插件
 * classpath，使 {@code SevenZip}/{@code DownloadUtil} 的静态状态各持一份，从而在单个 JVM 内
 * 忠实模拟“第 1 代 → 第 2 代”的换代过程——这正是缺陷的触发条件（同一路径在本进程被占用）。
 */
class SevenZipReinitializationTest {

    private static final String PREFIX = "nanogradle-sevenzip-";

    @TempDir
    Path tempDir;

    /**
     * 验证插件 classloader 换代后仍能完成解压，且两代使用不同的临时目录。
     *
     * @throws Exception 反射调用或 IO 异常
     */
    @Test
    void secondClassloaderGenerationCanStillExtract() throws Exception {
        Path archive = tempDir.resolve("payload");
        Files.write(archive, zipFixture());

        Set<String> before = listSevenZipTmpDirs();
        List<URL> urls = classpathUrls();

        // 第 1 代：守护进程里的第一次构建
        ClassLoader genA = newGeneration(urls);
        extractInGeneration(genA, archive, tempDir.resolve("out-a"));
        Set<String> createdA = difference(listSevenZipTmpDirs(), before);
        assertEquals(1, createdA.size(), "第 1 代应恰好新建一个 SevenZip 临时目录，实际：" + createdA);

        // 第 2 代：插件 classloader 被重建，DLL 仍被本进程映射 —— 修复前此处必失败
        ClassLoader genB = newGeneration(urls);
        assertNotSame(loadSevenZip(genA), loadSevenZip(genB), "测试前提：两代必须是彼此独立的 SevenZip 类");
        extractInGeneration(genB, archive, tempDir.resolve("out-b"));
        Set<String> createdB = difference(listSevenZipTmpDirs(), union(before, createdA));
        assertEquals(1, createdB.size(), "第 2 代应新建自己的临时目录，实际：" + createdB);

        assertTrue(Collections.disjoint(createdA, createdB), "两代不得复用同一原生库路径");
        String selfPrefix = PREFIX + ProcessHandle.current().pid() + "-";
        assertTrue(createdA.iterator().next().startsWith(selfPrefix), "临时目录应以自身 pid 命名：" + createdA);
        assertTrue(createdB.iterator().next().startsWith(selfPrefix), "临时目录应以自身 pid 命名：" + createdB);
    }

    /**
     * 在指定代中调用 {@code DownloadUtil.extractArchive}，并校验解压结果。
     * <p>
     * 日志用动态代理实现该代自己的 {@code Logger} 接口，避免跨 classloader 的类型不匹配。
     *
     * @param loader    该代的 classloader
     * @param archive   归档文件
     * @param outputDir 解压输出目录
     * @throws Exception 反射调用或 IO 异常
     */
    private static void extractInGeneration(ClassLoader loader, Path archive, Path outputDir) throws Exception {
        Class<?> utilClass = Class.forName("io.github.nanoforged.nanogradle.util.DownloadUtil", true, loader);
        Class<?> loggerType = Class.forName("org.gradle.api.logging.Logger", true, loader);
        Object logger = Proxy.newProxyInstance(loader, new Class<?>[]{loggerType}, (proxy, method, args) -> {
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            return null;
        });
        Method extract = utilClass.getMethod("extractArchive", Path.class, Path.class, loggerType);
        extract.invoke(null, archive, outputDir, logger);

        assertEquals("api-bytes", Files.readString(outputDir.resolve("starfarer.api.jar")));
        assertEquals("factions", Files.readString(outputDir.resolve("data/campaign/factions.csv")));
    }

    /**
     * 构造一个新“代”：同一份 classpath，但父加载器为平台加载器，因此插件类与 SevenZip 类都是独立副本。
     *
     * @param urls classpath 条目
     * @return 独立的 classloader
     */
    private static ClassLoader newGeneration(List<URL> urls) {
        return new URLClassLoader("nanogradle-gen", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    /**
     * 在指定代中取 {@code SevenZip} 类（不触发其静态初始化）。
     *
     * @param loader 该代的 classloader
     * @return SevenZip 类
     * @throws ClassNotFoundException 类不在该代 classpath 上
     */
    private static Class<?> loadSevenZip(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("net.sf.sevenzipjbinding.SevenZip", false, loader);
    }

    /**
     * 计算 {@code java.class.path} 与关键构件位置，供各代 classloader 使用。
     *
     * @return classpath 条目
     * @throws IOException 资源位置无法解析时抛出
     */
    private static List<URL> classpathUrls() throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        addCodeSource(entries, DownloadUtil.class);
        addCodeSource(entries, Logger.class);
        addCodeSource(entries, SevenZip.class);
        addCodeSource(entries, OkHttpClient.class);

        URL platforms = DownloadUtil.class.getClassLoader().getResource("sevenzipjbinding-platforms.properties");
        assertNotNull(platforms, "classpath 上必须存在 sevenzipjbinding-all-platforms jar");
        entries.add(locationOf(platforms));

        List<URL> urls = new ArrayList<>(entries.size());
        for (String entry : entries) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        return urls;
    }

    /**
     * 把类所在的位置（目录或 jar）加入条目集合，未知位置直接跳过。
     *
     * @param entries 条目集合
     * @param type    锚点类
     * @throws IOException 位置无法转换为本地路径时抛出
     */
    private static void addCodeSource(Set<String> entries, Class<?> type) throws IOException {
        var source = type.getProtectionDomain().getCodeSource();
        if (source != null) {
            entries.add(locationOf(source.getLocation()));
        }
    }

    /**
     * 把资源/jar 的 URL 解析为本地文件路径。
     *
     * @param url 形如 {@code jar:file:/C:/x.jar!/entry} 或 {@code file:/C:/classes/} 的 URL
     * @return 本地路径
     * @throws IOException 无法解析时抛出
     */
    private static String locationOf(URL url) throws IOException {
        String text = url.toString();
        int bang = text.indexOf("!/");
        if (bang >= 0) {
            text = text.substring(0, bang);
        }
        if (text.startsWith("jar:")) {
            text = text.substring("jar:".length());
        }
        try {
            return Path.of(URI.create(text)).toString();
        } catch (RuntimeException e) {
            throw new IOException("无法解析构件位置：" + url, e);
        }
    }

    /**
     * 列出临时目录下所有 SevenZip 解压目录（仅目录名）。
     *
     * @return 目录名集合
     * @throws IOException 无法列举临时目录时抛出
     */
    private static Set<String> listSevenZipTmpDirs() throws IOException {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (var entries = Files.list(tmpDir)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(PREFIX))
                    .collect(Collectors.toSet());
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(item -> !right.contains(item)).collect(Collectors.toSet());
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> merged = new LinkedHashSet<>(left);
        merged.addAll(right);
        return merged;
    }

    /**
     * 构造与 {@code DownloadUtilTest} 一致的最小归档 fixture。
     *
     * @return zip 字节
     * @throws IOException 写入失败
     */
    private static byte[] zipFixture() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            add(zos, "starfarer.api.jar", "api-bytes");
            add(zos, "data/campaign/factions.csv", "factions");
        }
        return bos.toByteArray();
    }

    private static void add(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
