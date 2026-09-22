# Git Line Blame

轻量的 IntelliJ IDEA 插件 —— 复刻 GitToolBox 最核心的「当前行行内 blame」功能：光标停在某一行时，在行尾用灰色小字显示该行**最近一次提交的作者、时间与提交信息**。

A lightweight IntelliJ IDEA plugin that reimplements GitToolBox's core in-line blame: when the caret is on a line, it shows the author, date-time and commit subject of that line's latest commit at the end of the line in gray.

## 为什么做这个 / Why

GitToolBox 官方插件的「当前行 blame」功能广受欢迎，但部分用户在新版 IDE 上遇到兼容问题。本插件基于官方公开的 VCS / Git API 重新实现这一最常用的能力，兼容 2026.1（261）及更新版本。

## 功能 / Features

- 光标所在行行尾显示 `作者 · 时间 · 提交信息`（提交信息截断 50 字符，超出加省略号）
- 对所有 git 跟踪文件生效
- 整文件 blame 结果按 `modificationStamp` 缓存，光标移动只查内存，不重复执行 `git blame`
- 光标快速移动时的异步竞态已处理（自增 requestId + 行号去重），不会出现「点一下有、点一下没有」

## 兼容性 / Compatibility

- `since-build`: **261**（IntelliJ IDEA 2026.1）
- `until-build`: **263.***（2026.3）
- 依赖 `Git4Idea`（git blame 实现所在 bundle 插件，为 IDE 标配）

## 技术要点 / Technical notes

- **行尾内联提示**：`EditorCustomElementRenderer`（官方 Inlay API，`addInlineElement`）
- **光标事件**：`CaretListener` 监听光标移动，仅重绘当前行；异步取数通过自增 requestId 校验，丢弃迟到的过期结果
- **blame 数据**：走通用 `AnnotationProvider.annotate(file)` 得到 `FileAnnotation`
  - author / date：`annotation.aspects` 中的 `AUTHOR` aspect + `annotation.getLineDate(line)`
  - commit subject：`GitFileAnnotation.getLineInfo(line).subject`（**`getRevisions()` 对 Git 返回空，不可用于取 subject**，这是关键坑）
- **全部官方公开 API**，不依赖 git4idea 内部实现，跨 261/262/263 稳定

## 目录结构 / Layout

```
git-line-blame/
├── build.gradle.kts              # IntelliJ Platform Gradle Plugin 2.2.1，target 263
├── settings.gradle.kts
├── gradle.properties
└── src/main/
    ├── kotlin/io/github/obedz/gitlineblame/
    │   ├── BlameCacheService.kt      # blame 查询 + 缓存（aspects + GitFileAnnotation）
    │   └── GitLineBlamePainter.kt    # 监听器 + 行尾渲染 + 光标监听
    └── resources/META-INF/
        ├── plugin.xml
        └── pluginIcon.svg
```

## 构建 / Build

```bash
cd git-line-blame
./gradlew buildPlugin -x buildSearchableOptions
# 产物在 build/distributions/git-line-blame-0.1.0.zip
```

> 注：`buildSearchableOptions` 在无头/沙箱环境可能崩溃（启动 JBR 沙箱 exit 134），自用无设置界面插件可跳过；正式上架前建议在完整 IDE 环境跑一次生成搜索索引。

## 安装 / Install

1. IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk…
2. 选择 `build/distributions/git-line-blame-0.1.0.zip`
3. 重启 IDEA

## License

[MIT](LICENSE)
