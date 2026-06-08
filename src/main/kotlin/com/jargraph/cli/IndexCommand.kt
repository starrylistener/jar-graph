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
        val (jarsToIndex, excludedJars) = if (jarPaths.isNotEmpty()) {
            jarPaths to emptyList()
        } else {
            val config = GraphConfig.load()
            if (config.classpath.isBlank()) {
                println("[ERROR] 未指定 JAR 路径，且未找到 init 配置，请先运行: jargraph init")
                return
            }
            val allJars = config.classpath.split(",")
            val excluded = config.excludedJars
            val selected = allJars.filter { jar -> excluded.none { jar.endsWith(it) || jar == it } }
            if (excluded.isNotEmpty()) {
                println("[INFO] 根据 init 配置跳过 ${excluded.size} 个 JAR，实际索引 ${selected.size} 个")
            }
            selected to excluded
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

        // 2. 预扫描：统计所有 class 文件总数
        val progress = ProgressBar()
        progress.start("Scanning JARs")
        var totalClasses = 0
        for ((idx, jarPath) in jarsToIndex.withIndex()) {
            val file = File(jarPath)
            if (file.exists()) {
                totalClasses += indexer.countClasses(jarPath)
            }
            progress.update(idx + 1, jarsToIndex.size)
        }
        progress.finish("Scanning JARs", "${ProgressBar.formatNumber(totalClasses)} classes")

        // 3. 解析所有 JAR
        progress.start("Parsing classes")
        val allResults = mutableListOf<IndexResult>()
        var parsedClasses = 0

        for (jarPath in jarsToIndex) {
            val file = File(jarPath)
            if (!file.exists()) {
                continue
            }

            indexer.onProgress = { current, _ ->
                parsedClasses++
                progress.update(parsedClasses, totalClasses)
            }
            val result = indexer.indexJar(jarPath, excludeNames)
            allResults.add(result)
        }
        indexer.onProgress = null
        progress.finish("Parsing classes", "${ProgressBar.formatNumber(parsedClasses)} classes")

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

        // 4. 写入数据库
        progress.start("Writing database")
        val mergedResult = IndexResult(nodes = allNodes, edges = distinctEdges, files = emptyList())
        val totalWriteItems = allNodes.size + distinctEdges.size
        writer.write(mergedResult) { current, _ ->
            progress.update(current, totalWriteItems)
        }
        val stats = writer.getStats()
        writer.close()
        progress.finish("Writing database", "${ProgressBar.formatNumber(stats.nodeCount.toInt())} nodes, ${ProgressBar.formatNumber(stats.edgeCount.toInt())} edges")

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
        if (excludedJars.isNotEmpty()) {
            println("  模式: 已跳过 ${excludedJars.size} 个未选中的 JAR")
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
