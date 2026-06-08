package com.jargraph.indexer

import org.objectweb.asm.*
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * JAR 包字节码分析器
 * 使用 ASM 提取类、方法、字段及调用关系
 * 支持嵌套 JAR（Spring Boot fat jar / WAR 等）
 */
class JarIndexer {

    fun indexJar(jarPath: String, excludeNames: Set<String> = emptySet()): IndexResult {
        val jarFile = File(jarPath)
        if (!jarFile.exists()) {
            throw IllegalArgumentException("JAR file not found: $jarPath")
        }
        return processJarFile(jarFile, jarFile.name, excludeNames)
    }

    private fun processJarFile(jarFile: File, namePrefix: String, excludeNames: Set<String>): IndexResult {
        val jar = JarFile(jarFile)
        val jarEntryPrefix = "jar:$namePrefix"
        val jarFileName = namePrefix.substringAfterLast('/')

        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val files = mutableListOf<JarFileRecord>()

        // JAR file 节点（使 contains 边合法）
        nodes.add(
            GraphNode(
                id = jarEntryPrefix,
                kind = "file",
                name = jarFileName,
                qualifiedName = jarEntryPrefix,
                filePath = jarEntryPrefix,
                language = "java",
                startLine = 0,
                endLine = 0,
                startColumn = 0,
                endColumn = 0,
                docstring = "[JAR] $jarFileName",
                updatedAt = System.currentTimeMillis()
            )
        )

        files.add(
            JarFileRecord(
                path = jarEntryPrefix,
                contentHash = sha256(jarFile.readBytes()),
                language = "java",
                size = jarFile.length(),
                modifiedAt = jarFile.lastModified(),
                indexedAt = System.currentTimeMillis(),
                nodeCount = 0
            )
        )

        val entries = jar.entries().toList()

        // 1. 处理 class 文件
        val classEntries = entries.filter { it.name.endsWith(".class") }
        for (entry in classEntries) {
            jar.getInputStream(entry).use { stream ->
                val classReader = ClassReader(stream.readAllBytes())
                val visitor = CodeGraphClassVisitor(
                    jarFileName = jarFileName,
                    jarEntryPrefix = jarEntryPrefix,
                    classFilePath = entry.name,
                    nodeCollector = nodes,
                    edgeCollector = edges,
                    excludeNames = excludeNames
                )
                classReader.accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
        }

        // 2. 处理嵌套 JAR
        val nestedJarEntries = entries.filter { !it.isDirectory && it.name.endsWith(".jar") }
        for (nestedEntry in nestedJarEntries) {
            val nestedPrefix = "$namePrefix!/${nestedEntry.name}"
            val tempFile = File.createTempFile("nested-", ".jar")
            tempFile.deleteOnExit()
            jar.getInputStream(nestedEntry).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val nestedResult = processJarFile(tempFile, nestedPrefix, excludeNames)
            nodes.addAll(nestedResult.nodes)
            edges.addAll(nestedResult.edges)
            files.addAll(nestedResult.files)
            tempFile.delete()
        }

        jar.close()

        // 更新 nodeCount（只统计本 JAR 层级直接包含的 class 节点）
        val nodeCount = nodes.count { it.filePath.startsWith(jarEntryPrefix) && it.kind != "file" }
        files[0] = files[0].copy(nodeCount = nodeCount)

        return IndexResult(nodes = nodes, edges = edges, files = files)
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * ASM ClassVisitor，提取 codegraph 兼容的节点和边
 *
 * ID 生成策略：只用 qualifiedName，不用 filePath。
 * 这样跨 class / 跨 JAR 的引用边才能匹配到实际节点。
 * 副作用：同一个 qualifiedName 出现在多个 JAR 中时后写入的会覆盖前者，
 * 但对于结构导航来说可接受（file_path 仍保留来源信息）。
 */
private class CodeGraphClassVisitor(
    private val jarFileName: String,
    private val jarEntryPrefix: String,
    private val classFilePath: String,
    private val nodeCollector: MutableList<GraphNode>,
    private val edgeCollector: MutableList<GraphEdge>,
    private val excludeNames: Set<String>
) : ClassVisitor(Opcodes.ASM9) {

    private lateinit var classId: String
    private lateinit var classQualifiedName: String
    private val classFileEntryPath = "$jarEntryPrefix!/$classFilePath"
    private var classVisibility: String? = null
    private var classKind: String = "class"
    private var skipClass = false

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        classQualifiedName = name.replace('/', '.')
        skipClass = excludeNames.contains(classQualifiedName)
        if (skipClass) {
            return
        }

        classVisibility = accessToVisibility(access)
        classKind = when {
            (access and Opcodes.ACC_ENUM) != 0 && name.endsWith("\$class") -> "enum"
            (access and Opcodes.ACC_ENUM) != 0 -> "enum"
            (access and Opcodes.ACC_INTERFACE) != 0 -> "interface"
            (access and Opcodes.ACC_ANNOTATION) != 0 -> "interface"
            else -> "class"
        }

        val doc = buildDocString(classKind, classQualifiedName, classVisibility, jarFileName)

        classId = makeId(classQualifiedName)

        nodeCollector.add(
            GraphNode(
                id = classId,
                kind = classKind,
                name = classQualifiedName.substringAfterLast('.'),
                qualifiedName = classQualifiedName,
                filePath = classFileEntryPath,
                language = "java",
                startLine = 0,
                endLine = 0,
                startColumn = 0,
                endColumn = 0,
                docstring = doc,
                signature = signature?.let { formatSignature(it) },
                visibility = classVisibility,
                isExported = (access and Opcodes.ACC_PUBLIC) != 0,
                isAbstract = (access and Opcodes.ACC_ABSTRACT) != 0,
                isStatic = (access and Opcodes.ACC_STATIC) != 0,
                updatedAt = System.currentTimeMillis()
            )
        )

        // jar contains class
        edgeCollector.add(
            GraphEdge(
                source = jarEntryPrefix,
                target = classId,
                kind = "contains"
            )
        )

        // extends
        if (superName != null && superName != "java/lang/Object") {
            val superQualified = superName.replace('/', '.')
            val superId = makeId(superQualified)
            edgeCollector.add(
                GraphEdge(
                    source = classId,
                    target = superId,
                    kind = "extends"
                )
            )
        }

        // implements
        interfaces?.forEach { iface ->
            val ifaceQualified = iface.replace('/', '.')
            val ifaceId = makeId(ifaceQualified)
            edgeCollector.add(
                GraphEdge(
                    source = classId,
                    target = ifaceId,
                    kind = "implements"
                )
            )
        }
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor? {
        if (skipClass) return null

        val fieldQualified = "$classQualifiedName::$name"
        val fieldId = makeId(fieldQualified)
        val visibility = accessToVisibility(access)
        val typeName = Type.getType(descriptor).className

        val doc = buildDocString("field", "$classQualifiedName.$name", visibility, jarFileName, typeName)

        nodeCollector.add(
            GraphNode(
                id = fieldId,
                kind = "field",
                name = name,
                qualifiedName = fieldQualified,
                filePath = classFileEntryPath,
                language = "java",
                startLine = 0,
                endLine = 0,
                startColumn = 0,
                endColumn = 0,
                docstring = doc,
                signature = typeName,
                visibility = visibility,
                isExported = (access and Opcodes.ACC_PUBLIC) != 0,
                isStatic = (access and Opcodes.ACC_STATIC) != 0,
                updatedAt = System.currentTimeMillis()
            )
        )

        edgeCollector.add(
            GraphEdge(
                source = classId,
                target = fieldId,
                kind = "contains"
            )
        )

        return null
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        if (skipClass) return null

        val methodDisplayName = when (name) {
            "<init>" -> "<init>"
            "<clinit>" -> "<clinit>"
            else -> name
        }
        val methodQualified = "$classQualifiedName::$methodDisplayName$descriptor"
        val methodId = makeId(methodQualified)

        val returnType = Type.getReturnType(descriptor).className
        val paramTypes = Type.getArgumentTypes(descriptor).map { it.className }
        val methodSignature = "$returnType $methodDisplayName(${paramTypes.joinToString(", ")})"
        val visibility = accessToVisibility(access)

        val doc = buildDocString("method", "$classQualifiedName.$methodDisplayName", visibility, jarFileName, methodSignature)

        nodeCollector.add(
            GraphNode(
                id = methodId,
                kind = "method",
                name = methodDisplayName,
                qualifiedName = methodQualified,
                filePath = classFileEntryPath,
                language = "java",
                startLine = 0,
                endLine = 0,
                startColumn = 0,
                endColumn = 0,
                docstring = doc,
                signature = methodSignature,
                visibility = visibility,
                isExported = (access and Opcodes.ACC_PUBLIC) != 0,
                isStatic = (access and Opcodes.ACC_STATIC) != 0,
                isAbstract = (access and Opcodes.ACC_ABSTRACT) != 0,
                updatedAt = System.currentTimeMillis()
            )
        )

        edgeCollector.add(
            GraphEdge(
                source = classId,
                target = methodId,
                kind = "contains"
            )
        )

        return MethodCallVisitor(methodId, edgeCollector)
    }

    private fun makeId(qualifiedName: String): String {
        return sha256(qualifiedName).take(32)
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun accessToVisibility(access: Int): String? = when {
        (access and Opcodes.ACC_PUBLIC) != 0 -> "public"
        (access and Opcodes.ACC_PROTECTED) != 0 -> "protected"
        (access and Opcodes.ACC_PRIVATE) != 0 -> "private"
        else -> "package"
    }

    private fun formatSignature(signature: String): String = signature

    private fun buildDocString(kind: String, name: String, visibility: String?, jarName: String, extra: String? = null): String {
        val vis = visibility ?: "package"
        return if (extra != null) {
            "[JAR] $jarName | $vis $kind $name — $extra"
        } else {
            "[JAR] $jarName | $vis $kind $name"
        }
    }
}

/**
 * ASM MethodVisitor，提取方法调用关系
 */
private class MethodCallVisitor(
    private val methodId: String,
    private val edgeCollector: MutableList<GraphEdge>
) : MethodVisitor(Opcodes.ASM9) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        val targetQualified = "${owner.replace('/', '.')}::$name$descriptor"
        val targetId = sha256(targetQualified).take(32)

        edgeCollector.add(
            GraphEdge(
                source = methodId,
                target = targetId,
                kind = "calls"
            )
        )
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        val targetQualified = "${owner.replace('/', '.')}::$name"
        val targetId = sha256(targetQualified).take(32)

        val edgeKind = when (opcode) {
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> "references"
            Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> "references"
            else -> "references"
        }

        edgeCollector.add(
            GraphEdge(
                source = methodId,
                target = targetId,
                kind = edgeKind
            )
        )
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

// ============== 数据模型 ==============

data class GraphNode(
    val id: String,
    val kind: String,
    val name: String,
    val qualifiedName: String,
    val filePath: String,
    val language: String,
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int,
    val endColumn: Int,
    val docstring: String? = null,
    val signature: String? = null,
    val visibility: String? = null,
    val isExported: Boolean = false,
    val isAsync: Boolean = false,
    val isStatic: Boolean = false,
    val isAbstract: Boolean = false,
    val decorators: String? = null,
    val typeParameters: String? = null,
    val updatedAt: Long
)

data class GraphEdge(
    val source: String,
    val target: String,
    val kind: String,
    val metadata: String? = null,
    val line: Int? = null,
    val col: Int? = null,
    val provenance: String? = null
)

data class JarFileRecord(
    val path: String,
    val contentHash: String,
    val language: String,
    val size: Long,
    val modifiedAt: Long,
    val indexedAt: Long,
    val nodeCount: Int,
    val errors: String? = null
)

data class IndexResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val files: List<JarFileRecord>
)
