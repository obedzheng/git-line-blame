import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.obedz"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 2026.3 目前是 EAP，无 "2026.3" 正式产物，须用明确的 build number 指定；
        // 本机为 IU-263.3889.65。
        create(IntelliJPlatformType.IntellijIdeaUltimate, "263.3889.65")
        // git4idea（Git blame 能力）在 2026.3 被拆到 vcs-git bundle 插件，需显式声明
        bundledPlugin("Git4Idea")
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.obedz.gitlineblame"

        name = "Git Line Blame"
        version = "0.1.0"

        // 兼容 2026.1 (261) 起的所有版本：261=2026.1、262=2026.2、263=2026.3。
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "263.*"
        }

        description = """
            Shows the author, date-time and commit subject of the latest commit
            for the current line, right at the end of the line in the editor.
            A lightweight alternative to GitToolBox's in-line blame.

            在编辑器行尾显示当前光标所在行的最近一次 git 提交的作者、时间与提交信息。
            轻量的 GitToolBox 行内 blame 替代方案。
        """.trimIndent()

        changeNotes = """
            First release.<br/>
            首个版本。
        """.trimIndent()

        vendor {
            name = "obedz"
            email = "obed.zhengchao@gmail.com"
            url = "https://github.com/obedzheng"
        }
    }

    pluginVerification {
        ides {
            // 二进制兼容性由 Plugin Verifier 检查。261/262 的 ideaIU 产物需从
            // JetBrains 官方仓库拉取，网络/仓库路径易导致解析失败。上架前建议在
            // 联网稳定的环境运行 `./gradlew verifyPlugin`；日常构建不受影响。
            recommended()
        }
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("261")
        untilBuild.set("263.*")
    }
}
