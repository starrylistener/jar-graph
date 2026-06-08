package com.jargraph.cli

import com.jargraph.config.GraphConfig
import com.jargraph.indexer.GraphEdge
import com.jargraph.indexer.GraphNode
import com.jargraph.indexer.IndexResult
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
        arity = "0..*",
        description = ["要分析的 JAR 文件路径（不指定则从 init 配置读取）"]
    )
    var jarPaths: List<String> = emptyList()

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
        val jarsToIndex = jarPaths.ifEmpty {
            val config = GraphConfig.load()
            if (config.classpath.isBlank()) {
                println("[ERROR] 未指定 JAR 路径，且未找到 init 配置，请先运行: jar-graph init")
                return
            }
            config.classpath.split(",")
        }

        // 1. 打开数据库并加载 exclude 名单
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        if (clear && outFile.exists()) {
            outFile.delete()
            println("[INFO] 已清空旧数据库")
        }

        val writer = CodeGraphDbWriter(outputPath).open()
        val indexer = JarIndexer()

        val excludeNames = if (excludeCovered) {
            val names = writer.getSourceQualifiedNames()
            if (names.isNotEmpty()) {
                println("[INFO] 检测到 ${names.size} 个源码节点，将跳过被覆盖的 JAR 类")
            }
            names
        } else {
            emptySet()
        }

        // 2. 收集所有 JAR 的分析结果
        println("[INFO] 开始分析 ${jarsToIndex.size} 个 JAR 包...")
        val allResults = mutableListOf<IndexResult>()

        for (jarPath in jarsToIndex) {
            val file = File(jarPath)
            if (!file.exists()) {
                println("[WARN] JAR 不存在，跳过: $jarPath")
                continue
            }

            println("[INFO] 分析: ${file.name}")
            val result = indexer.indexJar(jarPath, excludeNames)
            allResults.add(result)
            println("[INFO]   初步 nodes=${result.nodes.size}, edges=${result.edges.size}")
        }

        // 3. 全局合并：去重 nodes，过滤 dangling edges
        val nodeMap = mutableMapOf<String, GraphNode>()
        for (result in allResults) {
            for (node in result.nodes) {
                nodeMap[node.id] = node
            }
        }
        val allNodes = nodeMap.values.toList()
        val nodeIdSet = nodeMap.keys

        var filteredEdges = 0
        val allEdges = mutableListOf<GraphEdge>()
        for (result in allResults) {
            for (edge in result.edges) {
                if (edge.source in nodeIdSet && edge.target in nodeIdSet) {
                    allEdges.add(edge)
                } else {
                    filteredEdges++
                }
            }
        }

        // 去重：同一 source+target+kind 只保留一条
        val distinctEdges = allEdges.distinctBy { "${it.source}|${it.target}|${it.kind}" }
        val dedupedCount = allEdges.size - distinctEdges.size

        println("[INFO] 全局合并后 nodes=${allNodes.size}, 有效 edges=${distinctEdges.size}, 过滤 dangling=$filteredEdges, 去重=$dedupedCount")

        // 4. 一次性写入（不写 files 表，避免 codegraph sync 误删 JAR 数据）
        val mergedResult = IndexResult(nodes = allNodes, edges = distinctEdges, files = emptyList())
        writer.write(mergedResult)

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
