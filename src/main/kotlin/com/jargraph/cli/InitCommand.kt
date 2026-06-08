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
        // 从当前目录向上递归查找 pom.xml
        val projectRoot = findProjectRoot() ?: run {
            println("[ERROR] 当前目录及上级目录均未找到 pom.xml")
            return
        }

        val configFile = File(projectRoot, GraphConfig.CONFIG_PATH)
        if (configFile.exists() && !force) {
            println("[INFO] 配置已存在，使用 -f 强制重新初始化")
            return
        }

        println("[INFO] 解析项目: ${projectRoot.canonicalPath}")

        val mvnCmd = findMvn() ?: run {
            println("[ERROR] 未找到 mvn 或 mvnw 命令，请确保 Maven 已安装")
            println("[HINT] 解决方法（任选其一）：")
            println("  1) 将 mvn 加入系统 PATH")
            println("  2) 设置环境变量 JARGRAPH_MVN=/absolute/path/to/mvn")
            println("  3) 设置环境变量 M2_HOME=/path/to/maven")
            println("  4) 在项目根目录放置 mvnw（Maven Wrapper）")
            return
        }

        val tempFile = File.createTempFile("jar-graph-cp", ".txt")
        val process = ProcessBuilder(
            mvnCmd, "-q", "dependency:build-classpath",
            "-Dmdep.outputFile=${tempFile.absolutePath}"
        )
            .directory(projectRoot)
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
            projectPath = projectRoot.canonicalPath,
            classpath = jars.joinToString(",")
        )

        GraphConfig.save(config, projectRoot)
        println("[INFO] 配置已保存到: ${projectRoot.canonicalPath}/${GraphConfig.CONFIG_PATH}")
    }

    private fun findProjectRoot(): File? {
        var dir = File(".").canonicalFile
        while (true) {
            if (File(dir, "pom.xml").exists()) {
                return dir
            }
            val parent = dir.parentFile ?: break
            dir = parent
        }
        return null
    }

    private fun findMvn(): String? {
        val isWin = System.getProperty("os.name").lowercase().contains("win")

        // 1. launcher.js 注入的环境变量（优先）
        System.getenv("JARGRAPH_MVN")?.let { path ->
            if (File(path).exists()) return path
        }

        // 2. 从当前目录向上递归查找 mvnw（Maven Wrapper）
        var dir = File(".").canonicalFile
        while (dir.parentFile != null) {
            val mvnwNames = if (isWin) listOf("mvnw.cmd", "mvnw.bat", "mvnw") else listOf("mvnw")
            for (name in mvnwNames) {
                val mvnw = File(dir, name)
                if (mvnw.exists()) {
                    return mvnw.canonicalPath
                }
            }
            dir = dir.parentFile
        }

        // 3. 从 PATH 中查找 mvn / mvnw
        val mvnNames = if (isWin) listOf("mvn.cmd", "mvn.exe", "mvn") else listOf("mvn")
        for (name in mvnNames) {
            if (isCommandInPath(name)) return name
        }
        val mvnwNamesPath = if (isWin) listOf("mvnw.cmd", "mvnw.bat", "mvnw") else listOf("mvnw")
        for (name in mvnwNamesPath) {
            if (isCommandInPath(name)) return name
        }

        // 4. 探测常见安装路径
        val commonPaths = if (isWin) {
            listOfNotNull(
                System.getenv("M2_HOME")?.let { "$it\\bin\\mvn.cmd" },
                System.getenv("MAVEN_HOME")?.let { "$it\\bin\\mvn.cmd" },
                "C:\\Program Files\\Apache Software Foundation\\Maven\\bin\\mvn.cmd",
                "C:\\Program Files\\Maven\\bin\\mvn.cmd"
            )
        } else {
            listOfNotNull(
                System.getenv("M2_HOME")?.let { "$it/bin/mvn" },
                System.getenv("MAVEN_HOME")?.let { "$it/bin/mvn" },
                "/opt/homebrew/bin/mvn",
                "/usr/local/bin/mvn",
                "/usr/bin/mvn"
            )
        }

        return commonPaths.firstOrNull { File(it).exists() }
    }

    private fun isCommandInPath(cmd: String): Boolean {
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
