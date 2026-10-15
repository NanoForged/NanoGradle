package io.github.nanoforged.nanogradle;

import io.github.nanoforged.nanogradle.dsl.NanoSet;
import io.github.nanoforged.nanogradle.task.CollectArchiveTask;
import io.github.nanoforged.nanogradle.util.DownloadUtil;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * NanoGradle 插件入口。
 * <p>
 * 只注册<b>一个</b>任务 {@code collectArchive}（{@link CollectArchiveTask}）：
 * 取档 → 7z 解压 → 按 DSL 声明的集合收集到 {@code build/nanogradle/combined/<targetDir>/}。
 * <p>
 * 集合规则在任务执行期才读取，因此用户在任何时机配置都生效，无需 {@code afterEvaluate}。
 * 规则同时被规范化成 {@code @Input}（见 {@link #collectionRules}），保证改规则必然让任务重跑。
 */
public class NanoGradle implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        NanoGradleExtension extension =
                project.getExtensions().create("nanogradle", NanoGradleExtension.class, project);

        TaskProvider<CollectArchiveTask> collect = project.getTasks().register(
                NanoGradleDefaults.TASK_COLLECT, CollectArchiveTask.class, task -> {
                    task.setGroup(NanoGradleDefaults.GROUP);
                    task.setDescription("从远端存档解压并按 nanogradle DSL 收集内容到 combined/"
                            + "（7z 按内容识别格式，不支持即失败）");

                    Provider<String> version = extension.getVersion();
                    task.getVersion().set(version);
                    task.getUrl().set(extension.getUrl().zip(version, DownloadUtil::buildUrl));

                    task.getArchive().set(
                            project.getLayout().getBuildDirectory().file(NanoGradleDefaults.ARCHIVE_PATH));
                    task.getExtractedDir().convention(
                            project.getLayout().getBuildDirectory().dir(NanoGradleDefaults.EXTRACTED_DIR));
                    task.getCombinedDir().convention(
                            project.getLayout().getBuildDirectory().dir(NanoGradleDefaults.COMBINED_DIR));
                    task.getHttpCacheDir().set(project.getGradle().getGradleUserHomeDir());

                    task.getSets().put(NanoGradleDefaults.SET_RUNTIME, extension.getRuntimesConfig());
                    task.getSets().put(NanoGradleDefaults.SET_API, extension.getApiConfig());
                    task.getSets().put(NanoGradleDefaults.SET_RESOURCES, extension.getResourcesConfig());

                    task.getCollectionRules().set(project.provider(() -> collectionRules(extension)));
                    task.getHasCustomSpecs().set(project.provider(() -> hasCustomSpecs(extension)));
                });

        extension.setCollectTask(collect);
    }

    /**
     * 把三个集合的规则规范化成可指纹化的行列表。
     * <p>
     * 规则变化必须让任务重跑，否则收集产物会陈旧；每行形如
     * {@code <集合名>:<include|exclude|targetDir>:<值>}。
     *
     * @param extension 插件扩展（执行期读取，拿到用户最终配置）
     * @return 规范化规则行
     */
    private static List<String> collectionRules(NanoGradleExtension extension) {
        List<String> rules = new ArrayList<>();
        appendRules(rules, NanoGradleDefaults.SET_RUNTIME, extension.getRuntimesConfig());
        appendRules(rules, NanoGradleDefaults.SET_API, extension.getApiConfig());
        appendRules(rules, NanoGradleDefaults.SET_RESOURCES, extension.getResourcesConfig());
        return rules;
    }

    /**
     * 追加单个集合的规则行。
     *
     * @param rules 结果列表
     * @param name  集合名
     * @param set   集合规则
     */
    private static void appendRules(List<String> rules, String name, NanoSet set) {
        rules.add(name + ":targetDir:" + set.getTargetDir().getOrElse(""));
        for (String include : set.getIncludes()) {
            rules.add(name + ":include:" + include);
        }
        for (String exclude : set.getExcludes()) {
            rules.add(name + ":exclude:" + exclude);
        }
    }

    /**
     * 是否存在 Spec/闭包形式的规则。
     *
     * @param extension 插件扩展
     * @return 任意集合存在 Spec 规则返回 {@code true}
     */
    private static boolean hasCustomSpecs(NanoGradleExtension extension) {
        return hasCustomSpecs(extension.getRuntimesConfig())
                || hasCustomSpecs(extension.getApiConfig())
                || hasCustomSpecs(extension.getResourcesConfig());
    }

    /**
     * 单个集合是否存在 Spec 规则。
     *
     * @param set 集合规则
     * @return 存在 Spec 规则返回 {@code true}
     */
    private static boolean hasCustomSpecs(NanoSet set) {
        return !set.getIncludeSpecs().isEmpty() || !set.getExcludeSpecs().isEmpty();
    }
}
