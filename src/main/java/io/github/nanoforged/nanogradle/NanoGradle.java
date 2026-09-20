package io.github.nanoforged.nanogradle;

import io.github.nanoforged.nanogradle.dsl.NanoSet;
import io.github.nanoforged.nanogradle.task.DownloadTask;
import io.github.nanoforged.nanogradle.task.ExtractTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;

/**
 * NanoGradle 插件入口（见设计文档 §2.1、§3、§6.1）。
 * <p>
 * 任务流水线（全部惰性注册，仅在使用时被 realize）：
 * <pre>
 * downloadNsis (DownloadTask) → extractNsis (ExtractTask) → syncNanoRuntime / syncNanoApi / syncNanoResources (原生 Sync)
 * </pre>
 * 分类阶段直接复用 Gradle 原生 {@link Sync} 任务：
 * <ul>
 *   <li>每个集合一个 Sync，从 {@code extracted/} 按 include/exclude 规则分类复制；</li>
 *   <li>Sync 自带文件快照与目标目录镜像（删除陈旧文件）能力，无需自写复制逻辑；</li>
 *   <li>规则在 copy spec <b>执行期</b>惰性读取，天然拿到用户最终配置（见设计文档 §3.3、§5.2）。</li>
 * </ul>
 */
public class NanoGradle implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        NanoGradleExtension extension =
                project.getExtensions().create("nanogradle", NanoGradleExtension.class, project);

        // Download: build/nanogradle/Game.zip
        TaskProvider<DownloadTask> downloadNsis = project.getTasks().register(
                NanoGradleDefaults.TASK_DOWNLOAD, DownloadTask.class, task -> {
                    task.setGroup(NanoGradleDefaults.GROUP);
                    task.setDescription("下载游戏安装包到 Game.zip（OkHttp + 磁盘缓存；version 变更即重下）");
                    task.getVersion().convention(extension.getVersion());
                    task.getUrl().convention(extension.getUrl());
                    task.getHttpCacheDir().convention(project.getGradle().getGradleUserHomeDir());
                    task.getGameArchive().convention(
                            project.getLayout().getBuildDirectory().file(NanoGradleDefaults.ARCHIVE_PATH));
                });

        // Extract: build/nanogradle/extracted
        TaskProvider<ExtractTask> extractNsis = project.getTasks().register(
                NanoGradleDefaults.TASK_EXTRACT, ExtractTask.class, task -> {
                    task.setGroup(NanoGradleDefaults.GROUP);
                    task.setDescription("使用 SevenZipJBinding 解压 Game.zip（按内容自动识别 NSIS/zip 等格式）");
                    task.getGameArchive().set(downloadNsis.flatMap(DownloadTask::getGameArchive));
                    task.dependsOn(downloadNsis);
                    task.getExtractedDir().convention(
                            project.getLayout().getBuildDirectory().dir(NanoGradleDefaults.EXTRACTED_DIR));
                });

        // 分类 Sync：每个集合一个原生 Sync 任务（combined/<targetDir>）
        registerSync(project, extractNsis, NanoGradleDefaults.TASK_SYNC_RUNTIME,
                "分类复制 runtime 依赖 JAR 到 combined/runtime", extension.getRuntimesConfig());
        registerSync(project, extractNsis, NanoGradleDefaults.TASK_SYNC_API,
                "分类复制 api 依赖 JAR 到 combined/api", extension.getApiConfig());
        registerSync(project, extractNsis, NanoGradleDefaults.TASK_SYNC_RESOURCES,
                "分类复制资源到 combined/assets", extension.getResourcesConfig());
    }

    /**
     * 为一个集合注册原生 {@link Sync} 任务。
     * <p>
     * 输出目录为 {@code build/nanogradle/combined/<targetDir>}（每个集合独立，
     * 避免多个 Sync 共享同一输出根目录相互覆盖）；目标目录通过 provider 惰性解析，
     * 规则在 copy spec <b>执行期</b>惰性读取，因而无需 afterEvaluate；
     * 集合未配置任何 include/exclude 时任务被 {@code onlyIf} 跳过。
     *
     * @param project     项目
     * @param extractNsis 解压任务（输出 extracted/）
     * @param taskName    任务名
     * @param description 任务描述
     * @param set         集合配置
     * @return 任务提供者
     */
    private TaskProvider<Sync> registerSync(Project project, TaskProvider<ExtractTask> extractNsis,
                                            String taskName, String description, NanoSet set) {
        return project.getTasks().register(taskName, Sync.class, task -> {
            task.setGroup(NanoGradleDefaults.GROUP);
            task.setDescription(description);
            task.into(targetDirOf(project, set));
            task.dependsOn(extractNsis);
            task.from(extractNsis.flatMap(ExtractTask::getExtractedDir), spec -> applySpec(spec, set));
            task.onlyIf(t -> set.isConfigured());
        });
    }

    /**
     * 惰性解析 Sync 目标目录：{@code build/nanogradle/combined/<targetDir>}。
     *
     * @param project 项目
     * @param set     集合配置
     * @return 目标目录的惰性 {@link File}
     */
    private static Provider<File> targetDirOf(Project project, NanoSet set) {
        Provider<File> combined = project.getLayout().getBuildDirectory()
                .dir(NanoGradleDefaults.COMBINED_DIR)
                .map(Directory::getAsFile);
        return combined.zip(set.getTargetDir(), (root, sub) -> new File(root, sub));
    }

    /**
     * 把集合的 include/exclude（字符串模式 + Spec）应用到 Sync 的 copy spec。
     * <p>
     * 该方法在任务执行期调用：此时集合已被用户配置完毕。
     *
     * @param spec copy spec
     * @param set  集合配置
     */
    private static void applySpec(CopySpec spec, NanoSet set) {
        String[] includes = set.getIncludes().toArray(new String[0]);
        String[] excludes = set.getExcludes().toArray(new String[0]);
        if (includes.length > 0) {
            spec.include(includes);
        }
        if (excludes.length > 0) {
            spec.exclude(excludes);
        }
        for (Spec<FileTreeElement> includeSpec : set.getIncludeSpecs()) {
            spec.include(includeSpec);
        }
        for (Spec<FileTreeElement> excludeSpec : set.getExcludeSpecs()) {
            spec.exclude(excludeSpec);
        }
    }
}
