package io.github.nanoforged.nanogradle.task;

import io.github.nanoforged.nanogradle.util.DownloadUtil;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 解压任务：使用 SevenZipJBinding 解压游戏安装归档（NSIS 安装器 / zip 等，见设计文档 §3.2）。
 * <p>
 * 归档格式由 SevenZip 按内容自动识别，无需关注下载文件的扩展名。
 * <p>
 * 缓存策略：{@code @InputFile} 为归档文件（Gradle 按<b>内容哈希</b>判定变更），
 * {@code @OutputDirectory} 为 {@code build/nanogradle/extracted/}；
 * 仅当归档内容变化或输出目录缺失时才重新解压。每次真正执行前先清空输出目录，
 * 避免旧版本残留文件混入后续 Sync。
 */
public abstract class ExtractTask extends DefaultTask {

    /**
     * 注入构造器（Gradle 任务要求）。
     */
    @Inject
    public ExtractTask() {
    }

    /**
     * 输入：游戏安装归档（DownloadTask 的产物 {@code Game.zip}）。
     */
    @InputFile
    public abstract RegularFileProperty getGameArchive();

    /**
     * 输出：解压目录。
     */
    @OutputDirectory
    public abstract DirectoryProperty getExtractedDir();

    /**
     * 执行解压（先清空输出目录再解压）。
     *
     * @throws Exception 解压过程中的任意异常
     */
    @TaskAction
    public void extract() throws Exception {
        Path archive = getGameArchive().getAsFile().get().toPath();
        Path outputDir = getExtractedDir().getAsFile().get().toPath();
        deleteRecursively(outputDir);
        DownloadUtil.extractArchive(archive, outputDir, getLogger());
    }

    /**
     * 递归删除目录内容（保留目录本身）。
     *
     * @param dir 目标目录
     * @throws IOException IO 异常
     */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new IllegalStateException("nanogradle: 清理解压目录失败: " + p, e);
                }
            });
        }
    }
}
