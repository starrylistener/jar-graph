package com.jargraph.cli

import com.jargraph.config.*
import picocli.CommandLine
import java.io.File

@CommandLine.Command(
    name = "init",
    description = ["初始化知识图谱配置"]
)
class InitCommand : Runnable {

    @CommandLine.Option(
        names = ["-g", "--global"],
        description = ["全局模式：扫描整个 ~/.m2/repository"]
    )
    var global: Boolean = false

    @CommandLine.Option(
        names = ["-f", "--force"],
        description = ["强制跳过大小安全检查"]
    )
    var force: Boolean = false

    @CommandLine.Option(
        names = ["--max-size"],
        description = ["自定义安全阈值（GB），0 表示不限制"],
        defaultValue = "5"
    )
    var maxSizeGb: Int = 5

    @CommandLine.Option(
        names = ["--path"],
        description = ["自定义 JAR 目录路径"]
    )
    var customPath: String? = null

    override fun run() {
        val scope = when {
            customPath != null -> Scope.CUSTOM
            global -> Scope.GLOBAL
            else -> Scope.PROJECT
        }

        val m2Repo = File(System.getProperty("user.home"), ".m2/repository")
        val repoSizeGb = if (m2Repo.exists()) m2Repo.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum() / (1024.0 * 1024.0 * 1024.0) else 0.0

        println("[INFO] 初始化模式: ${scope.name.lowercase()}")

        if (scope == Scope.GLOBAL && !force && maxSizeGb > 0) {
            println("[INFO] 检测到 Maven 仓库: ${m2Repo.absolutePath}")
            println("[INFO] 仓库大小: %.1f GB".format(repoSizeGb))

            if (repoSizeGb > maxSizeGb) {
                println("[WARN] 仓库大小超过安全阈值 ($maxSizeGb GB)")
                println("[HINT] 使用 -f 强制扫描，或调整 --max-size")
                return
            }
        }

        val config = GraphConfig(
            scope = scope,
            m2Repo = m2Repo.absolutePath,
            maxSizeGb = maxSizeGb,
            forceScan = force
        )

        GraphConfig.save(config)
        println("[INFO] 配置已保存到: .jar-graph/config.properties")
    }
}
