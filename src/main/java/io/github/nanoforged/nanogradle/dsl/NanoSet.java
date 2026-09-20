package io.github.nanoforged.nanogradle.dsl;

import org.gradle.api.file.FileTreeElement;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.util.PatternFilterable;

import java.util.List;

/**
 * NanoGradle 集合统一抽象：{@link DependencySet}（JAR 依赖集）与 {@link ResourcesSet}（资源集）
 * 的共同能力，供插件以同一方式惰性配置原生 Sync 任务。
 * <p>
 * 除 {@link PatternFilterable} 的字符串模式外，还保留 Spec/闭包形式的规则；
 * 全部状态都在 Sync 的 copy spec 执行期才被读取。
 */
public interface NanoSet extends PatternFilterable {

    /**
     * Spec 形式的 include 规则（非字符串模式）。
     *
     * @return 规则列表
     */
    List<Spec<FileTreeElement>> getIncludeSpecs();

    /**
     * Spec 形式的 exclude 规则（非字符串模式）。
     *
     * @return 规则列表
     */
    List<Spec<FileTreeElement>> getExcludeSpecs();

    /**
     * 输出子目录名（相对 combined 根目录）。
     *
     * @return 惰性 {@link Property}
     */
    Property<String> getTargetDir();

    /**
     * 是否配置了任何 include/exclude 规则。
     *
     * @return 存在任意规则返回 {@code true}
     */
    boolean isConfigured();
}
