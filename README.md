# NanoGradle

 A Simple Version of `SectorDevGradle`
---

## 快速开始

在 `build.gradle` 中：

```groovy
plugins {
    id 'java'
    id 'io.github.nanoforged.nanogradle' version '1.0-SNAPSHOT'
}

nanogradle {
    version = "0.97a"   // 版本号：会替换 URL 中的 {version}
    // url 缺省指向官方 Windows 安装包地址：
    //   https://f005.backblazeb2.com/file/fractalsoftworks/release/starsector_install-{version}.exe
    runtimes {
        include "fs.common_obf.jar", "starfarer_obf.jar", "fs.sound_obf.jar"
        exclude "*-sources.jar"
        targetDir = "runtime"           // → build/nanogradle/combined/runtime
    }

    api {
        include "starfarer.api.jar"
        targetDir = "api"               // → build/nanogradle/combined/api
        sourceJar = "starfarer.api.zip" // 元数据，预留供未来扩展
    }

    resources {
        include "data/**", "graphics/**", "sounds/**"
        exclude "**/*.tmp"
        targetDir = "assets"            // → build/nanogradle/combined/assets
    }
}

dependencies {
    runtimeOnly nanogradle.runtimes      // FileCollection 访问器
    implementation nanogradle.api
}

```


## Task Pipeline

| 任务 | 类型               | 职责 |
| :--- |:-----------------| :--- |
| `downloadNsis` | `DownloadTask`   | OkHttp 下载游戏安装包到 `build/nanogradle/Game.zip` |
| `extractNsis` | `ExtractTask`    | SevenZipJBinding 解压 `Game.zip`（按内容自动识别 NSIS/zip 等）到 `build/nanogradle/extracted/` |
| `syncNanoRuntime` | Gradle 原生 `Sync` | 按 `runtimes` 规则分类复制 JAR 到 `combined/runtime/` |
| `syncNanoApi` | Gradle 原生 `Sync` | 按 `api` 规则分类复制 JAR 到 `combined/api/` |
| `syncNanoResources` | Gradle 原生 `Sync` | 按 `resources` 规则分类复制资源到 `combined/assets/` |

分类阶段**没有自定义复制逻辑**：直接复用 Gradle 原生 `Sync`，获得其增量复制与目标目录镜像（自动删除不再匹配的陈旧文件）能力。规则在 copy spec **执行期**惰性读取，因此无需 `afterEvaluate`，用户配置任何时机生效。

## 输出目录

```
build/nanogradle/
├── Game.zip            # 下载的游戏安装包落地文件（NSIS 安装器 exe / zip…），7z 按内容自动识别格式，扩展名仅为占位
├── extracted/          # 完整解压内容（extractNsis 每次执行前自动清空）
└── combined/
    ├── runtime/        # 运行时 JAR（fs.common_obf 等）
    ├── api/            # API JAR（starfarer.api.jar）
    └── assets/         # 资源根目录（data/ graphics/ sounds/…）
```

## DSL 参考

| 元素 | 说明 |
| :--- | :--- |
| `version` | 游戏版本号（必填）；替换 URL 中 `{version}`，同时是 downloadNsis 的独立输入（固定 URL 换版本也会重下） |
| `url` | 下载地址模板，默认指向官方分发地址 |
| `runtimes { }` / `api { }` | 依赖集，实现 Gradle 原生 `PatternFilterable` |
| `resources { }` | 资源集，同样支持 include/exclude |
| `include` / `exclude` | Ant 风格路径模式，**相对于解压根目录**（与 Gradle 拷贝任务语义一致，如 `data/**` 需带 `**` 才递归） |
| `targetDir` | 输出子目录名，默认 `runtime` / `api` / `assets` |
| `sourceJar` | api 关联源码归档名（元数据，暂不参与流水线） |
| `nanogradle.runtimes` / `nanogradle.api` | 惰性 `FileCollection`，携带任务依赖，可直接放进 `dependencies` |
| `nanogradle.resourcesDir` | 资源目录惰性集合 |

**惰性**：所有访问器内容在对应 Sync 执行后才解析，并通过 `builtBy` 自动建立任务依赖 —— 谁消费谁触发下载/解压/分类，缓存全命中时整条流水线 <1s（`UP-TO-DATE`）。

## 缓存与增量

| 层级 | 技术 | 说明 |
| :--- | :--- | :--- |
| 网络缓存 | OkHttp 磁盘缓存（`<gradle user home>/caches/okhttp`） | 跨构建、跨 `clean`，按 URL 隔离；服务器返回 304 时复用缓存体，返回 200 新内容时自动更新 |
| 任务缓存 | Gradle `@Input`/`@Output` | `version` 与最终 URL 均为输入：URL/版本不变 + 产物存在 → `downloadNsis` `UP-TO-DATE`；归档内容哈希不变 → 不解压 |
| 增量复制 | `Sync` 文件快照 | include/exclude 或源内容变化才重跑，并自动清理陈旧文件 |

- **版本号变更** → 触发重新下载：URL 含 `{version}` 时地址改变；即使使用固定镜像地址（不含占位符），`version` 也是独立输入，切换版本同样重下 —— 不会复用旧版本的本地产物；
- **同 URL 内容更新（服务器热更新）**：`downloadNsis` 处于 `UP-TO-DATE` 时不会感知；需要强拉可 `./gradlew clean` 或加 `--rerun-tasks`（此时 OkHttp 会按 HTTP 语义向服务器复验，命中 304 或拉取新内容）；
- **配置缓存**：插件全程使用 `Property`/`Provider` 惰性求值，任务执行期不触碰 `Task.project`，已通过 `--configuration-cache` 端到端测试。
