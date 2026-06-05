package com.jargraph.cli

import picocli.CommandLine
import kotlin.system.exitProcess

@CommandLine.Command(
    name = "jar-graph",
    mixinStandardHelpOptions = true,
    version = ["jar-graph 0.1.0"],
    description = ["本地 JAR 包知识图谱构建工具"],
    subcommands = [InitCommand::class, IndexCommand::class]
)
class JarGraphCli : Runnable {
    override fun run() {
        println("jar-graph: 请使用子命令，如: jar-graph init")
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(JarGraphCli()).execute(*args)
    exitProcess(exitCode)
}
