package io.github.nanoforged.nanogradle;

/**
 * NanoGradle 内部常量：任务名、默认配置与输出布局（见设计文档 §2.1、§3）。
 * <p>
 * 输出目录层级：{@code build/nanogradle/}{Game.zip, extracted, combined/...}。
 */
public final class NanoGradleDefaults {

    /** 任务分组名。 */
    public static final String GROUP = "nanogradle";

    /** 默认下载 URL 模板（真实官方分发地址，Backblaze B2，可含 {@code {version}} 占位符）。 */
    public static final String URL_TEMPLATE =
            "https://f005.backblazeb2.com/file/fractalsoftworks/release/starsector_install-{version}.exe";

    /** 下载任务名。 */
    public static final String TASK_DOWNLOAD = "downloadNsis";

    /** 解压任务名。 */
    public static final String TASK_EXTRACT = "extractNsis";

    /** runtime 依赖集 Sync 任务名。 */
    public static final String TASK_SYNC_RUNTIME = "syncNanoRuntime";

    /** api 依赖集 Sync 任务名。 */
    public static final String TASK_SYNC_API = "syncNanoApi";

    /** resources 资源集 Sync 任务名。 */
    public static final String TASK_SYNC_RESOURCES = "syncNanoResources";

    /**
     * 下载产物相对路径（build 目录下）。
     * <p>
     * 落地文件统一命名为 {@code Game.zip}：它只是本地占位名，内容即下载到的游戏安装包
     * （NSIS 安装器 exe / zip 等），解压时由 SevenZipJBinding 按内容自动识别格式，无需关心扩展名。
     */
    public static final String ARCHIVE_PATH = "nanogradle/Game.zip";

    /** 解压输出目录相对路径。 */
    public static final String EXTRACTED_DIR = "nanogradle/extracted";

    /** 分类合并输出根目录相对路径（{@code combined/<targetDir>}）。 */
    public static final String COMBINED_DIR = "nanogradle/combined";

    private NanoGradleDefaults() {
    }
}
