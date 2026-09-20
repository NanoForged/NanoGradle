package io.github.nanoforged.nanogradle.dsl;

import groovy.lang.Closure;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.util.PatternFilterable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 资源集描述：一组游戏资源目录的分类规则，实现 Gradle 原生 {@link PatternFilterable}。
 * <p>
 * 用户通过 DSL 声明式配置：
 * <pre>{@code
 * resources {
 *     include "data/**", "graphics/**", "sounds/**"
 *     exclude "*.tmp"
 *     targetDir = "assets"
 * }
 * }</pre>
 * include/exclude 采用 Gradle 拷贝任务的 Ant 风格路径模式；状态在 {@code Sync}
 * 任务的 copy spec 执行时才被惰性读取（见设计文档 §4.2、§5.2）。
 */
public class ResourcesSet implements NanoSet {

    private final Set<String> includes = new LinkedHashSet<>();
    private final Set<String> excludes = new LinkedHashSet<>();
    private final List<Spec<FileTreeElement>> includeSpecs = new ArrayList<>();
    private final List<Spec<FileTreeElement>> excludeSpecs = new ArrayList<>();
    private final Property<String> targetDir;

    /**
     * 由 Gradle {@link ObjectFactory} 实例化并注入。
     *
     * @param objects 对象工厂，用于创建惰性 {@link Property}
     */
    @Inject
    public ResourcesSet(ObjectFactory objects) {
        this.targetDir = objects.property(String.class);
    }

    /**
     * 输出子目录名（相对 combined 根目录），如 {@code assets}。
     */
    public Property<String> getTargetDir() {
        return targetDir;
    }

    /**
     * Groovy DSL 便捷赋值：{@code targetDir = "assets"}。
     *
     * @param targetDir 输出子目录名
     */
    public void setTargetDir(String targetDir) {
        this.targetDir.set(targetDir);
    }

    /**
     * 是否配置了任何 include/exclude 规则。
     *
     * @return 存在任意规则返回 {@code true}
     */
    public boolean isConfigured() {
        return !includes.isEmpty() || !excludes.isEmpty() || !includeSpecs.isEmpty() || !excludeSpecs.isEmpty();
    }

    @Override
    public Set<String> getIncludes() {
        return includes;
    }

    @Override
    public Set<String> getExcludes() {
        return excludes;
    }

    @Override
    public List<Spec<FileTreeElement>> getIncludeSpecs() {
        return includeSpecs;
    }

    @Override
    public List<Spec<FileTreeElement>> getExcludeSpecs() {
        return excludeSpecs;
    }

    @Override
    public PatternFilterable setIncludes(Iterable<String> includes) {
        this.includes.clear();
        for (String include : includes) {
            this.includes.add(include);
        }
        return this;
    }

    @Override
    public PatternFilterable setExcludes(Iterable<String> excludes) {
        this.excludes.clear();
        for (String exclude : excludes) {
            this.excludes.add(exclude);
        }
        return this;
    }

    @Override
    public PatternFilterable include(String... includes) {
        Collections.addAll(this.includes, includes);
        return this;
    }

    @Override
    public PatternFilterable include(Iterable<String> includes) {
        for (String include : includes) {
            this.includes.add(include);
        }
        return this;
    }

    @Override
    public PatternFilterable include(Spec<FileTreeElement> spec) {
        this.includeSpecs.add(spec);
        return this;
    }

    @Override
    public PatternFilterable include(Closure closure) {
        return include(toSpec(closure));
    }

    @Override
    public PatternFilterable exclude(String... excludes) {
        Collections.addAll(this.excludes, excludes);
        return this;
    }

    @Override
    public PatternFilterable exclude(Iterable<String> excludes) {
        for (String exclude : excludes) {
            this.excludes.add(exclude);
        }
        return this;
    }

    @Override
    public PatternFilterable exclude(Spec<FileTreeElement> spec) {
        this.excludeSpecs.add(spec);
        return this;
    }

    @Override
    public PatternFilterable exclude(Closure closure) {
        return exclude(toSpec(closure));
    }

    /**
     * 把 Groovy 闭包适配为以 {@link FileTreeElement} 为委托的 Spec。
     *
     * @param closure 用户闭包
     * @return 对应的 Spec
     */
    @SuppressWarnings("unchecked")
    private static Spec<FileTreeElement> toSpec(Closure closure) {
        return element -> {
            Closure<Object> copy = (Closure<Object>) closure.clone();
            copy.setResolveStrategy(Closure.DELEGATE_FIRST);
            copy.setDelegate(element);
            Object result = copy.call(element);
            return result instanceof Boolean && (Boolean) result;
        };
    }

    @Override
    public String toString() {
        return "ResourcesSet{includes=" + includes + ", excludes=" + excludes + ", targetDir=" + targetDir.getOrNull() + "}";
    }
}
