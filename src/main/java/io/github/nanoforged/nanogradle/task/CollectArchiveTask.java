package io.github.nanoforged.nanogradle.task;

import io.github.nanoforged.nanogradle.dsl.NanoSet;
import io.github.nanoforged.nanogradle.util.DownloadUtil;
import okhttp3.OkHttpClient;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 唯一任务：<b>从远端存档解压，并按 DSL 声明的规则收集内容</b>。
 * <p>
 * 一步完成三件事，取代原先 downloadNsis → extractNsis → syncNanoXxx 五任务流水线：
 * <ol>
 *   <li><b>取档</b>：按 {@code url} + {@code version} 下载到 {@code build/nanogradle/payload}
 *       （OkHttp 磁盘缓存位于 Gradle 用户主目录，跨构建与 {@code clean} 复用，命中缓存时不走网络）；</li>
 *   <li><b>解压</b>：SevenZipJBinding 解压到 {@code build/nanogradle/extracted/}，格式由 7-Zip
 *       按内容识别（NSIS 安装器 / zip / 7z …），不支持的格式直接抛异常；</li>
 *   <li><b>收集</b>：对每个已配置的集合，用 Gradle {@link FileSystemOperations#sync} 按
 *       include/exclude 规则镜像到 {@code build/nanogradle/combined/<targetDir>/}。</li>
 * </ol>
 *
 * <p>收集阶段复用 Gradle 原生 {@code sync}（而不是自写复制），因此 Ant 风格模式语义、
 * 目标目录镜像（自动删除不再匹配的陈旧文件）与原先的 {@code Sync} 任务完全一致；
 * 集合规则在任务<b>执行期</b>才读取，用户在任何时机配置都生效。
 *
 * <p><b>输入指纹</b>：{@code version}、解析后的最终 URL 与 {@link #getCollectionRules()} 都是
 * {@code @Input}，因此改版本、改 URL、改 include/exclude 都会让任务重跑；产物齐全且输入未变时为
 * {@code UP-TO-DATE}。Spec/闭包形式的规则无法被指纹化，一旦出现即保守地禁用 up-to-date
 * （见构造器中的 {@code upToDateWhen}）。
 */
public abstract class CollectArchiveTask extends DefaultTask {

    /**
     * 注入构造器（Gradle 任务要求），同时声明 Spec 规则存在时的保守 up-to-date 策略。
     */
    @Inject
    public CollectArchiveTask() {
        // Spec/闭包规则不可序列化、无法纳入输入指纹，检测到即禁用 up-to-date，避免产物陈旧
        getOutputs().upToDateWhen(task -> !((CollectArchiveTask) task).getHasCustomSpecs().getOrElse(false));
    }

    /**
     * 注入的文件系统操作服务：执行期做镜像复制与清理，配置缓存安全。
     *
     * @return 文件系统操作
     */
    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    /**
     * 游戏版本号，如 {@code 0.98a-RC8}。
     */
    @Input
    public abstract Property<String> getVersion();

    /**
     * 解析后的最终下载地址（{@code {version}} 占位符已替换）。
     */
    @Input
    public abstract Property<String> getUrl();

    /**
     * 集合规则的规范化描述（集合名 / targetDir / include / exclude 各一行）。
     * <p>
     * 用于把 DSL 规则纳入输入指纹：规则变化必须让任务重跑，否则产物会陈旧。
     */
    @Input
    public abstract ListProperty<String> getCollectionRules();

    /**
     * 是否存在 Spec/闭包形式的规则（存在时任务不做 up-to-date 判定）。
     */
    @Input
    public abstract Property<Boolean> getHasCustomSpecs();

    /**
     * 输出：下载到本地的远端存档（内容由 7-Zip 按内容识别格式，扩展名仅为占位）。
     */
    @OutputFile
    public abstract RegularFileProperty getArchive();

    /**
     * 输出：完整解压内容（任务执行前清空）。
     */
    @OutputDirectory
    public abstract DirectoryProperty getExtractedDir();

    /**
     * 输出：收集结果根目录，集合产物落于 {@code <combined>/<targetDir>/}。
     */
    @OutputDirectory
    public abstract DirectoryProperty getCombinedDir();

    /**
     * OkHttp 磁盘缓存所在目录（Gradle 用户主目录）。
     */
    @Internal
    public abstract Property<File> getHttpCacheDir();

    /**
     * 集合表：集合名 → DSL 规则对象。执行期读取，不参与指纹（规则经
     * {@link #getCollectionRules()} 指纹化）。
     */
    @Internal
    public abstract MapProperty<String, NanoSet> getSets();

    /**
     * 执行：取档 → 解压 → 按集合收集。
     *
     * @throws Exception 下载、解压或收集过程中的任意异常
     */
    @TaskAction
    public void collect() throws Exception {
        Logger logger = getLogger();
        Path archive = getArchive().get().getAsFile().toPath();
        Path extracted = getExtractedDir().get().getAsFile().toPath();
        Path combined = getCombinedDir().get().getAsFile().toPath();

        OkHttpClient client = DownloadUtil.newHttpClient(getHttpCacheDir().get());
        DownloadUtil.download(getUrl().get(), archive, client, logger);

        getFileSystemOperations().delete(spec -> spec.delete(extracted));
        DownloadUtil.extractArchive(archive, extracted, logger);

        for (Map.Entry<String, NanoSet> entry : getSets().get().entrySet()) {
            collectSet(entry.getKey(), entry.getValue(), extracted, combined, logger);
        }
    }

    /**
     * 把一个集合按规则镜像到 {@code <combined>/<targetDir>/}。
     * <p>
     * 未配置任何 include/exclude 的集合直接跳过（与原先 Sync 的 {@code onlyIf} 等价），
     * 避免把整个解压目录镜像进去。规则在此处（执行期）读取，因此用户配置总是生效。
     *
     * @param name      集合名（仅用于日志）
     * @param set       集合规则
     * @param extracted 解压根目录
     * @param combined  收集根目录
     * @param logger    Gradle 日志
     */
    private void collectSet(String name, NanoSet set, Path extracted, Path combined, Logger logger) {
        if (!set.isConfigured()) {
            logger.info("nanogradle: 集合 {} 未配置 include/exclude，跳过收集", name);
            return;
        }

        File source = extracted.toFile();
        File target = combined.resolve(set.getTargetDir().get()).toFile();
        String[] includes = set.getIncludes().toArray(new String[0]);
        String[] excludes = set.getExcludes().toArray(new String[0]);
        List<Spec<FileTreeElement>> includeSpecs = List.copyOf(set.getIncludeSpecs());
        List<Spec<FileTreeElement>> excludeSpecs = List.copyOf(set.getExcludeSpecs());

        getFileSystemOperations().sync(spec -> {
            spec.from(source);
            spec.into(target);
            if (includes.length > 0) {
                spec.include(includes);
            }
            if (excludes.length > 0) {
                spec.exclude(excludes);
            }
            includeSpecs.forEach(spec::include);
            excludeSpecs.forEach(spec::exclude);
        });

        logger.lifecycle("nanogradle: 集合 {} 收集完成 → {}", name, target);
    }
}
