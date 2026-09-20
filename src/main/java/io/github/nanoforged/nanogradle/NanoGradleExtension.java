package io.github.nanoforged.nanogradle;

import io.github.nanoforged.nanogradle.dsl.DependencySet;
import io.github.nanoforged.nanogradle.dsl.ResourcesSet;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;

/**
 * NanoGradle DSL 扩展与访问器（见设计文档 §2.1、§4）。
 * <p>
 * 顶层配置：
 * <pre>{@code
 * nanogradle {
 *     version = "0.97a"
 *     url = "https://f005.backblazeb2.com/file/fractalsoftworks/release/starsector_install-{version}.exe"
 *
 *     runtimes {
 *         include "fs.common_obf.jar", "starfarer_obf.jar"
 *         exclude "*-sources.jar"
 *         targetDir = "runtime"
 *     }
 *     api {
 *         include "starfarer.api.jar"
 *         targetDir = "api"
 *         sourceJar = "starfarer.api.zip"
 *     }
 *     resources {
 *         include "data/**", "graphics/**", "sounds/**"
 *         exclude "*.tmp"
 *         targetDir = "assets"
 *     }
 * }
 * }</pre>
 * 访问器全部<b>惰性</b>：文件内容在对应 Sync 任务执行后解析，且通过 {@code builtBy}
 * 携带任务依赖，因此可直接在 dependencies / sourceSets 中使用：
 * <pre>{@code
 * dependencies {
 *     runtimeOnly nanogradle.runtimes
 *     implementation nanogradle.api
 * }
 * sourceSets.main.resources.srcDir nanogradle.resourcesDir
 * }</pre>
 */
public class NanoGradleExtension {

    private final Project project;
    private final Property<String> version;
    private final Property<String> url;

    private final DependencySet runtimes;
    private final DependencySet api;
    private final ResourcesSet resources;

    /** runtime 依赖 JAR 集合（combined/runtime 下的 *.jar），首次访问时惰性构建。 */
    private FileCollection runtimesFiles;
    /** api 依赖 JAR 集合（combined/api 下的 *.jar），首次访问时惰性构建。 */
    private FileCollection apiFiles;
    /** 资源目录集合（combined/assets，单一目录），首次访问时惰性构建。 */
    private FileCollection resourcesDir;

    /**
     * 由插件通过 {@code extensions.create} 实例化。
     *
     * @param project 目标项目
     */
    public NanoGradleExtension(Project project) {
        this.project = project;
        ObjectFactory objects = project.getObjects();

        this.version = objects.property(String.class);
        this.url = objects.property(String.class);
        this.url.convention(NanoGradleDefaults.URL_TEMPLATE);

        this.runtimes = objects.newInstance(DependencySet.class);
        this.api = objects.newInstance(DependencySet.class);
        this.resources = objects.newInstance(ResourcesSet.class);
        runtimes.getTargetDir().convention("runtime");
        api.getTargetDir().convention("api");
        resources.getTargetDir().convention("assets");
    }

    /**
     * 游戏版本号，如 {@code 0.97a}。
     */
    public Property<String> getVersion() {
        return version;
    }

    /**
     * 下载 URL 模板（默认已指向官方分发地址，可含 {@code {version}} 占位符）。
     */
    public Property<String> getUrl() {
        return url;
    }

    /**
     * Groovy DSL 便捷赋值：{@code version = "0.97a"}。
     *
     * @param version 版本号
     */
    public void setVersion(String version) {
        this.version.set(version);
    }

    /**
     * Groovy DSL 便捷赋值：{@code url = "https://..."}。
     *
     * @param url 下载地址模板
     */
    public void setUrl(String url) {
        this.url.set(url);
    }

    // --- 集合配置块 ---

    /**
     * 配置 runtime 依赖集（{@code include}/{@code exclude}/{@code targetDir}）。
     *
     * @param action 配置动作
     */
    public void runtimes(Action<? super DependencySet> action) {
        action.execute(runtimes);
    }

    /**
     * Groovy DSL 便捷重载：{@code runtimes { ... }}，闭包以 {@link DependencySet} 为委托。
     *
     * @param closure 配置闭包
     */
    public void runtimes(Closure<?> closure) {
        runtimes(configureWith(runtimes, closure));
    }

    /**
     * 配置 api 依赖集（额外支持 {@code sourceJar} 元数据）。
     *
     * @param action 配置动作
     */
    public void api(Action<? super DependencySet> action) {
        action.execute(api);
    }

    /**
     * Groovy DSL 便捷重载：{@code api { ... }}，闭包以 {@link DependencySet} 为委托。
     *
     * @param closure 配置闭包
     */
    public void api(Closure<?> closure) {
        api(configureWith(api, closure));
    }

    /**
     * 配置资源集。
     *
     * @param action 配置动作
     */
    public void resources(Action<? super ResourcesSet> action) {
        action.execute(resources);
    }

    /**
     * Groovy DSL 便捷重载：{@code resources { ... }}，闭包以 {@link ResourcesSet} 为委托。
     *
     * @param closure 配置闭包
     */
    public void resources(Closure<?> closure) {
        resources(configureWith(resources, closure));
    }

    /**
     * 把以 {@code target} 为委托的闭包适配为 {@link Action}。
     *
     * @param target  配置目标
     * @param closure 配置闭包
     * @param <T>     配置目标类型
     * @return 适配后的 Action
     */
    private static <T> Action<? super T> configureWith(T target, Closure<?> closure) {
        return ignored -> {
            Closure<?> copy = (Closure<?>) closure.clone();
            copy.setResolveStrategy(Closure.DELEGATE_FIRST);
            copy.setDelegate(target);
            copy.call(target);
        };
    }

    // --- 惰性访问器（见设计文档 §4.3） ---

    /**
     * runtime 依赖 JAR 的惰性 {@link FileCollection}（Sync 执行后解析，自带任务依赖）。
     * <p>
     * 集合在首次访问时才构建（此时 Sync 任务已由插件注册完毕），之后缓存复用。
     *
     * @return JAR 文件集合，可直接用于 {@code dependencies { runtimeOnly nanogradle.runtimes }}
     */
    public FileCollection getRuntimes() {
        if (runtimesFiles == null) {
            runtimesFiles = jarFileCollection(NanoGradleDefaults.TASK_SYNC_RUNTIME);
        }
        return runtimesFiles;
    }

    /**
     * api 依赖 JAR 的惰性 {@link FileCollection}（Sync 执行后解析，自带任务依赖）。
     *
     * @return JAR 文件集合，可直接用于 {@code dependencies { implementation nanogradle.api }}
     */
    public FileCollection getApi() {
        if (apiFiles == null) {
            apiFiles = jarFileCollection(NanoGradleDefaults.TASK_SYNC_API);
        }
        return apiFiles;
    }

    /**
     * 资源输出目录的惰性 {@link FileCollection}（Sync 执行后解析，自带任务依赖）。
     * <p>
     * 集合内容为单一目录（默认 {@code combined/assets}），可直接用于
     * {@code sourceSets.main.resources.srcDir nanogradle.resourcesDir}。
     *
     * @return 资源目录集合
     */
    public FileCollection getResourcesDir() {
        if (resourcesDir == null) {
            resourcesDir = dirFileCollection(NanoGradleDefaults.TASK_SYNC_RESOURCES);
        }
        return resourcesDir;
    }

    // --- 内部：供插件注册 Sync 任务时读取规则 ---

    /**
     * runtime 依赖集配置对象（仅同包插件使用）。
     */
    DependencySet getRuntimesConfig() {
        return runtimes;
    }

    /**
     * api 依赖集配置对象（仅同包插件使用）。
     */
    DependencySet getApiConfig() {
        return api;
    }

    /**
     * 资源集配置对象（仅同包插件使用）。
     */
    ResourcesSet getResourcesConfig() {
        return resources;
    }

    // --- 惰性文件集合构建 ---

    /**
     * 构建 JAR 文件集合：Sync 输出目录（{@code combined/<targetDir>}）下所有 {@code *.jar}。
     * <p>
     * 目录来自对应 Sync 任务的输出，全部惰性解析，并携带该任务的构建依赖。
     *
     * @param syncTaskName Sync 任务名
     * @return 带任务依赖的 JAR 文件集合
     */
    private FileCollection jarFileCollection(String syncTaskName) {
        TaskProvider<Sync> syncTask = project.getTasks().named(syncTaskName, Sync.class);
        ConfigurableFileTree jarTree = project.fileTree(syncTask.map(Sync::getDestinationDir));
        jarTree.include("**/*.jar");
        return jarTree.builtBy(syncTask);
    }

    /**
     * 构建目录文件集合：Sync 输出目录本身（{@code combined/<targetDir>}）。
     *
     * @param syncTaskName Sync 任务名
     * @return 带任务依赖的目录文件集合
     */
    private FileCollection dirFileCollection(String syncTaskName) {
        TaskProvider<Sync> syncTask = project.getTasks().named(syncTaskName, Sync.class);
        return project.files(syncTask.map(Sync::getDestinationDir)).builtBy(syncTask);
    }
}
