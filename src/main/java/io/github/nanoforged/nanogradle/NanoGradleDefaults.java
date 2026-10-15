package io.github.nanoforged.nanogradle;

/**
 * NanoGradle 内部常量：任务名、默认配置与输出布局。
 * <p>
 * 输出目录层级：{@code build/nanogradle/}{payload, extracted, combined/...}。
 */
public final class NanoGradleDefaults {

    /** 任务分组名。 */
    public static final String GROUP = "nanogradle";

    /** 默认下载 URL 模板（真实官方分发地址，Backblaze B2，可含 {@code {version}} 占位符）。 */
    public static final String URL_TEMPLATE =
            "https://f005.backblazeb2.com/file/fractalsoftworks/release/starsector_install-{version}.exe";

    /**
     * 唯一任务名：从远端存档解压并按 DSL 收集内容。
     * <p>
     * 取档、解压、分类收集全部由这一个任务完成；格式由 7-Zip 按内容识别，
     * 不支持的格式直接失败。
     */
    public static final String TASK_COLLECT = "collectArchive";

    /**
     * 存档落地文件相对路径（build 目录下）。
     * <p>
     * 统一命名为 {@code payload}：它只是本地占位名，内容即下载到的游戏安装包
     * （NSIS 安装器 exe / zip 等），解压时由 SevenZipJBinding 按内容自动识别格式，扩展名无意义。
     */
    public static final String ARCHIVE_PATH = "nanogradle/payload";

    /** 解压输出目录相对路径。 */
    public static final String EXTRACTED_DIR = "nanogradle/extracted";

    /** 收集输出根目录相对路径（{@code combined/<targetDir>}）。 */
    public static final String COMBINED_DIR = "nanogradle/combined";

    /** runtime 依赖集在集合表中的键名。 */
    public static final String SET_RUNTIME = "runtimes";

    /** api 依赖集在集合表中的键名。 */
    public static final String SET_API = "api";

    /** resources 资源集在集合表中的键名。 */
    public static final String SET_RESOURCES = "resources";

    private NanoGradleDefaults() {
    }
}
