package com.jargraph.cli

import com.jargraph.config.GraphConfig
import picocli.CommandLine
import java.io.File

@CommandLine.Command(
    name = "init",
    description = ["初始化项目知识图谱配置，解析 pom.xml 及传递依赖"]
)
class InitCommand : Runnable {

    @CommandLine.Option(
        names = ["-f", "--force"],
        description = ["强制重新初始化，覆盖已有配置"]
    )
    var force: Boolean = false

    override fun run() {
        val pomFile = File("pom.xml")
        if (!pomFile.exists()) {
            println("[ERROR] 当前目录未找到 pom.xml，请在 Maven 项目根目录执行")
            return
        }

        val configFile = File(GraphConfig.CONFIG_PATH)
        if (configFile.exists() && !force) {
            println("[INFO] 配置已存在，使用 -f 强制重新初始化")
            return
        }

        println("[INFO] 解析项目: ${pomFile.canonicalPath}")

        val mvnCmd = findMvn() ?: run {
            println("[ERROR] 未找到 mvn 或 mvnw 命令，请确保 Maven 已安装")
            return
        }

        val tempFile = File.createTempFile("jar-graph-cp", ".txt")
        val process = ProcessBuilder(
            mvnCmd, "-q", "dependency:build-classpath",
            "-Dmdep.outputFile=${tempFile.absolutePath}"
        )
            .directory(File("."))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            println("[ERROR] 执行 '$mvnCmd dependency:build-classpath' 失败（exit=$exitCode）")
            println("[HINT] 请确保项目可编译：mvn compile -q")
            tempFile.delete()
            return
        }

        val classpath = tempFile.readText().trim()
        tempFile.delete()

        val jars = classpath.split(File.pathSeparator).filter { it.isNotBlank() }
        println("[INFO] 解析到 ${jars.size} 个依赖 JAR（含传递依赖）")

        if (jars.isNotEmpty()) {
            jars.take(5).forEach { println("[INFO]   $it") }
            if (jars.size > 5) {
                println("[INFO]   ... 共 ${jars.size} 个")
            }
        }

        val config = GraphConfig(
            projectPath = File(".").canonicalPath,
            classpath = jars.joinToString(",")
        )

        GraphConfig.save(config)
        println("[INFO] 配置已保存到: ${GraphConfig.CONFIG_PATH}")
    }

    private fun findMvn(): String? {
        return listOf("mvn", "mvnw").firstOrNull { isCommandAvailable(it) }
    }

    private fun isCommandAvailable(cmd: String): Boolean {
        return try {
            ProcessBuilder(cmd, "-v")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
