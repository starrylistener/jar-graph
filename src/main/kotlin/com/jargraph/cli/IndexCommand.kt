package com.jargraph.cli

import com.jargraph.indexer.JarIndexer
import com.jargraph.storage.CodeGraphDbWriter
import picocli.CommandLine
import java.io.File

@CommandLine.Command(
    name = "index",
    description = ["分析 JAR 包并构建 codegraph 兼容的知识图谱"]
)
class IndexCommand : Runnable {

    @CommandLine.Parameters(
        arity = "1..*",
        description = ["要分析的 JAR 文件路径"]
    )
    lateinit var jarPaths: List<String>

    @CommandLine.Option(
        names = ["-o", "--output"],
        description = ["输出数据库路径，默认: .codegraph/codegraph.db"],
        defaultValue = ".codegraph/codegraph.db"
    )
    var outputPath: String = ".codegraph/codegraph.db"

    @CommandLine.Option(
        names = ["--clear"],
        description = ["清空已有数据库后重建"]
    )
    var clear: Boolean = false

    @CommandLine.Option(
        names = ["--exclude-covered"],
        description = ["跳过已被项目源码覆盖的类（避免重复节点）"]
    )
    var excludeCovered: Boolean = false

    override fun run() {
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        if (clear && outFile.exists()) {
            outFile.delete()
            println("[INFO] 已清空旧数据库")
        }

        val writer = CodeGraphDbWriter(outputPath).open()
        val indexer = JarIndexer()

        // 加载已有源码节点的 qualified_name（用于排除被覆盖的类）
        val excludeNames = if (excludeCovered) {
            val names = writer.getSourceQualifiedNames()
            if (names.isNotEmpty()) {
                println("[INFO] 检测到 ${names.size} 个源码节点，将跳过被覆盖的 JAR 类")
            }
            names
        } else {
            emptySet()
        }

        println("[INFO] 开始分析 ${jarPaths.size} 个 JAR 包...")

        var totalSkipped = 0

        for (jarPath in jarPaths) {
            val file = File(jarPath)
            if (!file.exists()) {
                println("[WARN] JAR 不存在，跳过: $jarPath")
                continue
            }

            println("[INFO] 分析: ${file.name}")
            val result = indexer.indexJar(jarPath, excludeNames)
            writer.write(result)

            // 估算跳过数量（只统计 class 节点中不在结果里的）
            val skippedInJar = if (excludeCovered) {
                // 这里无法精确统计，因为跳过发生在 ClassVisitor 内部
                // 输出 nodes 数量即可，用户可对比预期
                0
            } else 0

            println("[INFO]   nodes=${result.nodes.size}, edges=${result.edges.size}")
        }

        val stats = writer.getStats()
        writer.close()

        println()
        println("=" * 40)
        println("索引完成")
        println("  数据库: $outputPath")
        println("  节点: ${stats.nodeCount}")
        println("  边: ${stats.edgeCount}")
        println("  文件: ${stats.fileCount}")
        if (excludeCovered) {
            println("  模式: 已跳过源码覆盖的类")
        }
        println()
        println("节点分布:")
        stats.nodesByKind.toSortedMap().forEach { (kind, count) ->
            println("  $kind: $count")
        }
        println()
        println("边分布:")
        stats.edgesByKind.toSortedMap().forEach { (kind, count) ->
            println("  $kind: $count")
        }
        println("=" * 40)
    }

    private operator fun String.times(n: Int): String = repeat(n)
}
