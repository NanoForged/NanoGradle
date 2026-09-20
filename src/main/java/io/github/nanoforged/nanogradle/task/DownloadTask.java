package io.github.nanoforged.nanogradle.task;

import io.github.nanoforged.nanogradle.NanoGradleDefaults;
import io.github.nanoforged.nanogradle.util.DownloadUtil;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;

/**
 * 下载任务：从远程 URL 下载游戏安装归档（NSIS 安装器 exe / zip 等）到本地（见设计文档 §3.1）。
 * <p>
 * 缓存策略为「双层缓存」：
 * <ul>
 *   <li><b>Gradle 任务缓存</b>：{@code version} 与 {@link #getResolvedUrl() 最终地址} 均为任务输入，
 *       输出文件 {@code build/nanogradle/Game.zip} 存在且输入不变时任务 {@code UP-TO-DATE}；
 *       切换版本号（即使 URL 固定、镜像地址不含版本占位符）也会触发重新下载；</li>
 *   <li><b>OkHttp 磁盘缓存</b>：位于 {@code <gradle user home>/caches/okhttp}，按 URL 隔离；
 *       服务器按 HTTP 缓存语义（ETag/Last-Modified）决定返回 304 还是 200 新内容，
 *       即使 clean 或强制重跑也不会盲目全量重下（见设计文档 §5.1）。</li>
 * </ul>
 * 所有状态在配置期注入（Property 惰性解析），执行期不访问 {@code Task.project}，兼容配置缓存。
 */
public abstract class DownloadTask extends DefaultTask {

    /**
     * 注入构造器（Gradle 任务要求）。
     */
    @Inject
    public DownloadTask() {
    }

    /**
     * 游戏版本号。
     * <p>
     * 独立作为任务输入：版本切换（即使 URL 模板不含 {@code {version}} 占位符）必然触发重新下载，
     * 避免"固定镜像地址 + 换版本"时错误复用旧版本的缓存产物。
     */
    @Input
    public abstract Property<String> getVersion();

    /**
     * 下载 URL 模板，可含 {@code {version}} 占位符（不含占位符的固定地址同样支持）。
     */
    @Internal
    public abstract Property<String> getUrl();

    /**
     * 下载产物：游戏安装归档，默认 {@code build/nanogradle/Game.zip}。
     * <p>
     * 文件名为通用占位，实际内容格式由 SevenZip 解压时按内容自动识别（无需关心扩展名）。
     */
    @OutputFile
    public abstract RegularFileProperty getGameArchive();

    /**
     * OkHttp 磁盘缓存目录（默认 {@code <gradle user home>/caches/okhttp}），配置期注入。
     */
    @Internal
    public abstract Property<File> getHttpCacheDir();

    /**
     * 解析后的最终下载地址（版本号已替换占位符），作为任务输入参与缓存判定。
     *
     * @return 最终下载 URL
     */
    @Input
    public String getResolvedUrl() {
        String template = getUrl().getOrNull();
        String version = getVersion().getOrNull();
        if (template == null || version == null) {
            throw new GradleException("nanogradle: 尚未配置 version/url。请在 nanogradle {} 中设置游戏版本号与下载地址，"
                    + "例如 version = \"0.98a-RC8\"、url = \"" + NanoGradleDefaults.URL_TEMPLATE + "\"");
        }
        return DownloadUtil.buildUrl(template, version);
    }

    /**
     * 执行下载。
     *
     * @throws Exception 网络或 IO 异常
     */
    @TaskAction
    public void download() throws Exception {
        File cacheDir = getHttpCacheDir().getOrNull();
        if (cacheDir == null) {
            cacheDir = new File(System.getProperty("user.home"), ".gradle");
        }
        DownloadUtil.download(
                getResolvedUrl(),
                getGameArchive().getAsFile().get().toPath(),
                DownloadUtil.newHttpClient(cacheDir),
                getLogger());
    }
}
