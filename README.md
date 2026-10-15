# NanoGradle

 A Simple Version of `SectorDevGradle`

 AI Slop.

## Usage

in `settings.gradle`
```groovy
pluginManagement {
        maven {
            name = "JitPack"
            url = "https://jitpack.io" // Required because we don't have a Maven repository yet.
        }
}
```


in  `build.gradle` :

```groovy
plugins {
    id 'java'
    id 'io.github.nanoforged.nanogradle' version '-SNAPSHOT' // latest JitPack snapshot
}


nanogradle {
    version = "0.98a-RC8"
    // default:
    // url = https://f005.backblazeb2.com/file/fractalsoftworks/release/starsector_install-{version}.exe
    
    // game(0.98a) mirror of fossic 
    // url = "https://cdn.fossic.cn/forum/202504/19/101929anj9i6zjidhinnnl.attach"
    runtimes {
        include "starsector-core/fs.common_obf.jar",
                "starsector-core/starfarer_obf.jar",
                "starsector-core/fs.sound_obf.jar"
        exclude "**/*-sources.jar"
        targetDir = "runtime"
    }

    api {
        include "starsector-core/starfarer.api.jar"
        targetDir = "api"
        sourceJar = "starsector-core/starfarer.api.zip"
    }

    resources {
        include "starsector-core/data/**",
                "starsector-core/graphics/**",
                "starsector-core/sounds/**"
        exclude "**/*.tmp"
        targetDir = "assets"
    }
}

dependencies {
    runtimeOnly nanogradle.runtimes
    implementation nanogradle.api
}

```


## Task

**只有一个任务：`collectArchive`**（`CollectArchiveTask`），一步完成取档 → 解压 → 收集：

| 阶段 | 实现 | 产物 |
| :--- | :--- | :--- |
| 取档 | OkHttp 下载（磁盘缓存，304 复用） | `build/nanogradle/payload` |
| 解压 | SevenZipJBinding，格式由 7-Zip **按内容识别**（NSIS 安装器 / zip / 7z …），不支持的格式直接失败 | `build/nanogradle/extracted/` |
| 收集 | Gradle `FileSystemOperations.sync`，按 DSL 的 include/exclude 镜像 | `build/nanogradle/combined/<targetDir>/` |

收集阶段**没有自定义复制逻辑**：直接复用 Gradle 原生 `sync`，获得其目标目录镜像（自动删除不再匹配的陈旧文件）能力；Ant 风格模式语义与原生 `Sync` 完全一致。集合规则在任务**执行期**才读取，因此无需 `afterEvaluate`，用户配置任何时机生效。

## 输出目录

```
build/nanogradle/
├── payload            # 下载的游戏安装包落地文件（NSIS 安装器 exe / zip…），7z 按内容自动识别格式，扩展名仅为占位
├── extracted/          # 完整解压内容（collectArchive 每次执行前自动清空）
└── combined/
    ├── runtime/        # 运行时 JAR（fs.common_obf 等）
    ├── api/            # API JAR（starfarer.api.jar）
    └── assets/         # 资源根目录（data/ graphics/ sounds/…）
```

## DSL 参考

| 元素 | 说明 |
| :--- | :--- |
| `version` | 游戏版本号（必填）；替换 URL 中 `{version}`，同时是收集任务的独立输入（固定 URL 换版本也会重下） |
| `url` | 下载地址模板，默认指向官方分发地址 |
| `runtimes { }` / `api { }` | 依赖集，实现 Gradle 原生 `PatternFilterable` |
| `resources { }` | 资源集，同样支持 include/exclude |
| `include` / `exclude` | Ant 风格路径模式，**相对于解压根目录**（与 Gradle 拷贝任务语义一致，如 `data/**` 需带 `**` 才递归） |
| `targetDir` | 输出子目录名，默认 `runtime` / `api` / `assets` |
| `sourceJar` | api 关联源码归档名（元数据，暂不参与流水线） |
| `nanogradle.runtimes` / `nanogradle.api` | 惰性 `FileCollection`，携带任务依赖，可直接放进 `dependencies` |
| `nanogradle.resourcesDir` | 资源目录惰性集合 |

**惰性**：所有访问器内容在收集任务执行后才解析，并通过 `builtBy` 自动建立任务依赖 —— 谁消费谁触发取档/解压/收集，缓存全命中时整个任务 <1s（`UP-TO-DATE`）。

## 缓存与增量

| 层级 | 技术 | 说明 |
| :--- | :--- | :--- |
| 网络缓存 | OkHttp 磁盘缓存（`<gradle user home>/caches/okhttp`） | 跨构建、跨 `clean`，按 URL 隔离；服务器返回 304 时复用缓存体，返回 200 新内容时自动更新 |
| 任务缓存 | Gradle `@Input`/`@Output` | 输入 = `version` + 最终 URL + 规范化后的集合规则；产物齐全且输入未变 → `collectArchive` `UP-TO-DATE` |
| 收集镜像 | `FileSystemOperations.sync` | include/exclude 变化即重跑，并自动删除目标目录中不再匹配的陈旧文件 |

- **版本号变更** → 触发重新取档：URL 含 `{version}` 时地址改变；即使使用固定镜像地址（不含占位符），`version` 也是独立输入，切换版本同样重取 —— 不会复用旧版本的本地产物；
- **include/exclude 变更** → 规范化规则行作为输入参与指纹，任务必然重跑，产物不会陈旧；
- **Spec/闭包形式的规则**无法被指纹化，一旦使用即保守地禁用 up-to-date（每次构建都重跑），避免静默产出陈旧内容；
- **同 URL 内容更新（服务器热更新）**：`collectArchive` 处于 `UP-TO-DATE` 时不会感知；需要强拉可 `./gradlew clean` 或加 `--rerun-tasks`（此时 OkHttp 会按 HTTP 语义向服务器复验，命中 304 或拉取新内容）；
- **配置缓存**：插件全程使用 `Property`/`Provider` 惰性求值，任务执行期不触碰 `Task.project`，已通过 `--configuration-cache` 端到端测试。
