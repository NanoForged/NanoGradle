package io.github.nanoforged.nanogradle;

import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NanoGradle 插件端到端功能测试（TestKit + 本地 HTTP fixture，无需外网）。
 * <p>
 * 覆盖：全链路下载→解压→分类 Sync→依赖/资源接入、UP-TO-DATE 增量、HTTP 304 磁盘缓存、
 * 版本变更重下载、配置缓存兼容性。
 */
class NanoGradleFunctionalTest {

    /**
     * TestKit 工作根目录（放在插件项目 build/ 下，避免 JUnit 清理与常驻 daemon 文件锁冲突）。
     */
    private static final Path WORK = Path.of(System.getProperty("user.dir"), "build", "testkit-work").toAbsolutePath();

    static {
        try {
            Files.createDirectories(WORK.resolve("testkit"));
            Files.createDirectories(WORK.resolve("gradle-home"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Path testKitDir = WORK.resolve("testkit");
    private final Path gradleUserHome = WORK.resolve("gradle-home");
    private HttpFixture http;

    @BeforeEach
    void setUp() throws IOException {
        http = new HttpFixture(zipForVersion("0.97a"), zipForVersion("0.98a"));
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    // ------------------------------------------------------------------
    // 测试 1：全链路 + 增量
    // ------------------------------------------------------------------

    @Test
    void fullPipelineDownloadsExtractsAndSyncs() throws IOException {
        Path project = newProject("consumer1");
        writeDefaultBuild(project, "0.97a");

        BuildResult result = build(project, "jar", "resolveRuntime");
        assertTask(result, ":downloadNsis", TaskOutcome.SUCCESS);
        assertTask(result, ":extractNsis", TaskOutcome.SUCCESS);
        assertTask(result, ":syncNanoRuntime", TaskOutcome.SUCCESS);
        assertTask(result, ":syncNanoApi", TaskOutcome.SUCCESS);
        assertTask(result, ":syncNanoResources", TaskOutcome.SUCCESS);

        // 下载产物
        assertTrue(Files.isRegularFile(project.resolve("build/nanogradle/Game.zip")));
        // 解压产物
        assertTrue(Files.isRegularFile(project.resolve("build/nanogradle/extracted/starfarer.api.jar")));
        assertTrue(Files.isRegularFile(project.resolve("build/nanogradle/extracted/data/campaign/factions.csv")));

        // 分类输出：runtime 只含被 include 的两个 JAR，其余文件不得混入
        assertEquals(
                Set.of("fs.common_obf.jar", "starfarer_obf.jar"),
                listNames(project.resolve("build/nanogradle/combined/runtime")));
        assertEquals(
                Set.of("starfarer.api.jar"),
                listNames(project.resolve("build/nanogradle/combined/api")));

        // 资源分类：data/graphics/sounds 进入 assets，*.tmp 被排除，深层目录不被带入
        Path assets = project.resolve("build/nanogradle/combined/assets");
        assertTrue(Files.isRegularFile(assets.resolve("data/campaign/factions.csv")));
        assertTrue(Files.isRegularFile(assets.resolve("graphics/logo.png")));
        assertTrue(Files.isRegularFile(assets.resolve("sounds/amb.ogg")));
        assertFalse(Files.exists(assets.resolve("data/trash.tmp")));
        assertFalse(Files.exists(assets.resolve("deep")));

        // 依赖/资源接入：jar 中包含资源，说明 srcDir + builtBy 依赖链成立
        List<String> jarEntries = jarEntries(project.resolve("build/libs/consumer-1.0.jar"));
        assertTrue(jarEntries.contains("data/campaign/factions.csv"));
        assertTrue(jarEntries.contains("graphics/logo.png"));
        assertFalse(jarEntries.contains("data/trash.tmp"));

        // 网络请求：整个构建仅一次下载
        assertEquals(1, http.requestCount());
    }

    @Test
    void secondBuildIsFullyUpToDate() throws IOException {
        Path project = newProject("consumer2");
        writeDefaultBuild(project, "0.97a");

        build(project, "jar", "resolveRuntime");
        BuildResult result = build(project, "jar", "resolveRuntime");

        assertTask(result, ":downloadNsis", TaskOutcome.UP_TO_DATE);
        assertTask(result, ":extractNsis", TaskOutcome.UP_TO_DATE);
        assertTask(result, ":syncNanoRuntime", TaskOutcome.UP_TO_DATE);
        assertTask(result, ":syncNanoApi", TaskOutcome.UP_TO_DATE);
        assertTask(result, ":syncNanoResources", TaskOutcome.UP_TO_DATE);
        assertEquals(1, http.requestCount(), "第二次构建不应产生任何网络请求");
    }

    // ------------------------------------------------------------------
    // 测试 2：版本变更 → URL 变更 → 重新下载；回退 → UP-TO-DATE
    // ------------------------------------------------------------------

    @Test
    void versionChangeTriggersRerunAndRevertIsUpToDate() throws IOException {
        Path project = newProject("consumer3");
        writeDefaultBuild(project, "0.97a");
        build(project, "jar", "resolveRuntime");

        // 0.98a：URL 变更 → 下载重跑；内容也变更 → 解压、Sync 重跑
        writeDefaultBuild(project, "0.98a");
        BuildResult changed = build(project, "jar", "resolveRuntime");
        assertTask(changed, ":downloadNsis", TaskOutcome.SUCCESS);
        assertTask(changed, ":extractNsis", TaskOutcome.SUCCESS);
        assertTask(changed, ":syncNanoResources", TaskOutcome.SUCCESS);

        // 回退 0.97a：URL 相对 0.98a 历史再次变更 → 重跑一次
        writeDefaultBuild(project, "0.97a");
        BuildResult reverted = build(project, "jar", "resolveRuntime");
        assertTask(reverted, ":downloadNsis", TaskOutcome.SUCCESS);
        assertTask(reverted, ":extractNsis", TaskOutcome.SUCCESS);

        // 稳定后再构建：回到 UP-TO-DATE，且此后不再产生网络请求
        BuildResult stable = build(project, "jar", "resolveRuntime");
        assertTask(stable, ":downloadNsis", TaskOutcome.UP_TO_DATE);
        assertTask(stable, ":extractNsis", TaskOutcome.UP_TO_DATE);
        assertEquals(3, http.requestCount());
    }

    /**
     * 固定 URL（镜像，不含 {version}）场景：version 仍是独立输入，
     * 切换版本必然触发重新下载（Gradle 层防缓存过时）。
     */
    @Test
    void versionIsInputEvenForFixedUrl() throws IOException {
        Path project = newProject("consumer6");
        writeDefaultBuild(project, "0.97a", true);
        BuildResult first = build(project, "jar");
        assertTask(first, ":downloadNsis", TaskOutcome.SUCCESS);
        assertEquals(1, http.requestCount());

        // URL 不变仅换版本 → downloadNsis 必须重新执行（version 输入变更）
        writeDefaultBuild(project, "0.98a", true);
        BuildResult changed = build(project, "jar");
        assertTask(changed, ":downloadNsis", TaskOutcome.SUCCESS);
        assertEquals(2, http.requestCount(), "固定 URL 下切换版本应重下（OkHttp 304 复验）");
        assertEquals("304", http.lastResponseCode());

        // 再稳定一次 → UP-TO-DATE
        BuildResult stable = build(project, "jar");
        assertTask(stable, ":downloadNsis", TaskOutcome.UP_TO_DATE);
        assertEquals(2, http.requestCount());
    }

    // ------------------------------------------------------------------
    // 测试 3：OkHttp 磁盘缓存 / 304（clean 与 --rerun-tasks 不重新下载全量）
    // ------------------------------------------------------------------

    @Test
    void httpDiskCacheSurvivesCleanAndForceRerun() throws IOException {
        Path project = newProject("consumer4");
        writeDefaultBuild(project, "0.97a");
        build(project, "jar");
        assertEquals(1, http.requestCount());
        assertEquals("200", http.lastResponseCode(), "首次构建应为全量 200");

        // clean 后重跑：downloadNsis 重新执行，但 OkHttp 磁盘缓存命中 → 304 条件请求
        BuildResult afterClean = build(project, "clean", "jar");
        assertTask(afterClean, ":downloadNsis", TaskOutcome.SUCCESS);
        assertTask(afterClean, ":extractNsis", TaskOutcome.SUCCESS);
        assertEquals(2, http.requestCount(), "clean 后应仅发生一次条件请求");
        assertEquals("304", http.lastResponseCode(), "clean 后重跑应命中 304 而非全量 200");

        // 强制重跑：downloadNsis 再次执行，仍为 304 条件请求
        BuildResult forced = build(project, "--rerun-tasks", "jar");
        assertTask(forced, ":downloadNsis", TaskOutcome.SUCCESS);
        assertEquals(3, http.requestCount());
        assertEquals("304", http.lastResponseCode(), "强制重跑应命中 304 而非全量 200");

        // 下载产物完好
        assertTrue(Files.isRegularFile(project.resolve("build/nanogradle/Game.zip")));
    }

    // ------------------------------------------------------------------
    // 测试 4：配置缓存兼容性
    // ------------------------------------------------------------------

    @Test
    void configurationCacheIsSupported() throws IOException {
        Path project = newProject("consumer5");
        writeDefaultBuild(project, "0.97a");

        BuildResult first = build(project, "--configuration-cache", "jar", "resolveRuntime");
        assertTask(first, ":downloadNsis", TaskOutcome.SUCCESS);
        assertTask(first, ":syncNanoRuntime", TaskOutcome.SUCCESS);

        BuildResult second = build(project, "--configuration-cache", "jar", "resolveRuntime");
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                "第二次构建应复用配置缓存条目，实际输出:\n" + second.getOutput());
        assertTask(second, ":downloadNsis", TaskOutcome.UP_TO_DATE);
        assertTask(second, ":syncNanoRuntime", TaskOutcome.UP_TO_DATE);

        // 分类产物仍在
        assertEquals(
                Set.of("fs.common_obf.jar", "starfarer_obf.jar"),
                listNames(project.resolve("build/nanogradle/combined/runtime")));
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static Path newProject(String name) throws IOException {
        // 追加纳秒后缀，避免多次运行相互污染
        Path project = Files.createDirectories(WORK.resolve("projects").resolve(name + "-" + System.nanoTime()));
        Files.writeString(project.resolve("settings.gradle"),
                "rootProject.name = 'consumer'\n");
        return project;
    }

    /**
     * 写默认构建脚本（端口已由 fixture 确定），URL 默认含 {version} 占位符。
     */
    private void writeDefaultBuild(Path project, String version) throws IOException {
        writeDefaultBuild(project, version, false);
    }

    /**
     * 写默认构建脚本；{@code fixedUrl} 为 {@code true} 时 URL 不含版本占位符（镜像场景）。
     */
    private void writeDefaultBuild(Path project, String version, boolean fixedUrl) throws IOException {
        String url = fixedUrl
                ? "http://127.0.0.1:%d/nsis.zip".formatted(http.port())
                : "http://127.0.0.1:%d/nsis-{version}.zip".formatted(http.port());
        String build = """
                plugins {
                    id 'java'
                    id 'io.github.nanoforged.nanogradle'
                }

                version = '1.0'

                nanogradle {
                    version = '%s'
                    url = '%s'

                    runtimes {
                        include 'fs.common_obf.jar', 'starfarer_obf.jar'
                        targetDir = 'runtime'
                    }
                    api {
                        include 'starfarer.api.jar'
                        targetDir = 'api'
                        sourceJar = 'starfarer.api.zip'
                    }
                    resources {
                        include 'data/**', 'graphics/**', 'sounds/**'
                        exclude '**/*.tmp'
                        targetDir = 'assets'
                    }
                }

                dependencies {
                    runtimeOnly nanogradle.runtimes
                    implementation nanogradle.api
                }

                sourceSets {
                    main {
                        resources {
                            srcDir nanogradle.resourcesDir
                        }
                    }
                }

                // 消费 runtimeClasspath（作为任务输入），确保 runtimeOnly 声明进入执行图
                tasks.register('resolveRuntime') {
                    inputs.files(configurations.runtimeClasspath)
                    doLast {
                        println 'RUNTIME=' + inputs.files.files*.name.sort().join(',')
                    }
                }
                """.formatted(version, url);
        Files.writeString(project.resolve("build.gradle"), build);
    }

    private BuildResult build(Path project, String... tasks) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withTestKitDir(testKitDir.toFile())
                .withArguments(argList(tasks))
                .withPluginClasspath()
                .build();
    }

    private List<String> argList(String[] tasks) {
        List<String> args = new java.util.ArrayList<>(Arrays.asList(tasks));
        args.add("--stacktrace");
        args.add("-g");
        args.add(gradleUserHome.toString());
        args.add("--offline");
        return args;
    }

    private static void assertTask(BuildResult result, String taskPath, TaskOutcome outcome) {
        var taskResult = result.task(taskPath);
        assertNotNull(taskResult, "任务 " + taskPath + " 未出现在执行结果中，完整输出:\n" + result.getOutput());
        assertEquals(outcome, taskResult.getOutcome(), "任务 " + taskPath + " 结果不符，完整输出:\n" + result.getOutput());
    }

    private static Set<String> listNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static List<String> jarEntries(Path jar) throws IOException {
        try (var zf = new java.util.zip.ZipFile(jar.toFile())) {
            return zf.stream().map(ZipEntry::getName).collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------
    // fixture：zip 内容
    // ------------------------------------------------------------------

    private static byte[] zipForVersion(String version) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            add(zos, "starfarer.api.jar", "api-jar-content-" + version);
            add(zos, "fs.common_obf.jar", "common-jar-content-" + version);
            add(zos, "starfarer_obf.jar", "starfarer-jar-content-" + version);
            add(zos, "fs.sound_obf.jar", "sound-jar-content-" + version);
            add(zos, "starfarer.api.zip", "api-source-zip-" + version);
            add(zos, "data/campaign/factions.csv", "factions-" + version);
            add(zos, "data/trash.tmp", "tmp");
            add(zos, "graphics/logo.png", "png");
            add(zos, "sounds/amb.ogg", "ogg");
            add(zos, "deep/inner.jar", "nested-jar");
            add(zos, "deep/keep.tmp", "tmp");
            add(zos, "VERSION-" + version + ".txt", version);
        }
        return bos.toByteArray();
    }

    private static void add(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ------------------------------------------------------------------
    // fixture：支持 304 条件请求的本地 HTTP 服务
    // ------------------------------------------------------------------

    private static final class HttpFixture implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> lastResponseCode = new AtomicReference<>("none");
        private final byte[] payloadA;
        private final byte[] payloadB;

        HttpFixture(byte[] payloadA, byte[] payloadB) throws IOException {
            this.payloadA = payloadA;
            this.payloadB = payloadB;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                boolean isB = path.contains("0.98a");
                String etag = isB ? "\"v98\"" : "\"v97\"";
                if (exchange.getRequestHeaders().getFirst("If-None-Match") != null) {
                    // 客户端（OkHttp）发出条件 GET → 一律回 304 空 body
                    lastResponseCode.set("304");
                    exchange.sendResponseHeaders(304, -1);
                } else {
                    byte[] body = isB ? payloadB : payloadA;
                    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    exchange.getResponseHeaders().add("ETag", etag);
                    exchange.getResponseHeaders().add("Cache-Control", "no-cache");
                    lastResponseCode.set("200");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        int requestCount() {
            return requests.get();
        }

        /**
         * 最近一次响应的状态码（"200" 或 "304"），用于证明缓存命中走的是条件请求。
         */
        String lastResponseCode() {
            return lastResponseCode.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
