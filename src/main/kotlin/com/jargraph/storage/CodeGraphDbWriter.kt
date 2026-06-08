package com.jargraph.storage

import com.jargraph.indexer.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * 将 JAR 分析结果写入 codegraph 兼容的 SQLite 数据库
 *
 * 复用 codegraph 的 schema，使原版 codegraph serve --mcp 可直接读取
 */
class CodeGraphDbWriter(private val dbPath: String) {

    private var connection: Connection? = null

    fun open(): CodeGraphDbWriter {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        // WAL 必须在 autoCommit=true / 事务外设置
        connection?.createStatement()?.use { it.execute("PRAGMA journal_mode=WAL") }
        connection?.createStatement()?.use { it.execute("PRAGMA synchronous=NORMAL") }
        connection?.autoCommit = false
        initSchema()
        return this
    }

    fun close() {
        connection?.close()
    }

    /** 分批写入的批次大小 */
    private val BATCH_SIZE = 50000

    /**
     * 写入完整的分析结果
     * @param onProgress (current, total) -> Unit，total 为 nodes+edges 总数
     */
    fun write(result: IndexResult, onProgress: ((current: Int, total: Int) -> Unit)? = null) {
        val conn = connection ?: throw IllegalStateException("Database not opened")

        // 清理已有的 JAR 数据，防止重复 index 导致 edges 累积
        conn.createStatement().use { stmt ->
            stmt.execute("DELETE FROM edges WHERE source IN (SELECT id FROM nodes WHERE file_path LIKE 'jar:%')")
            stmt.execute("DELETE FROM edges WHERE target IN (SELECT id FROM nodes WHERE file_path LIKE 'jar:%')")
            stmt.execute("DELETE FROM nodes WHERE file_path LIKE 'jar:%'")
            stmt.execute("DELETE FROM files WHERE path LIKE 'jar:%'")
        }

        val totalItems = result.nodes.size + result.edges.size
        var written = 0

        // 1. 写入 files
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO files
            (path, content_hash, language, size, modified_at, indexed_at, node_count, errors)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            for (file in result.files) {
                stmt.setString(1, file.path)
                stmt.setString(2, file.contentHash)
                stmt.setString(3, file.language)
                stmt.setLong(4, file.size)
                stmt.setLong(5, file.modifiedAt)
                stmt.setLong(6, file.indexedAt)
                stmt.setInt(7, file.nodeCount)
                stmt.setString(8, file.errors)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        // 2. 写入 nodes（触发 FTS5 同步触发器）
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO nodes
            (id, kind, name, qualified_name, file_path, language,
             start_line, end_line, start_column, end_column,
             docstring, signature, visibility,
             is_exported, is_async, is_static, is_abstract,
             decorators, type_parameters, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            var batchCount = 0
            for (node in result.nodes) {
                stmt.setString(1, node.id)
                stmt.setString(2, node.kind)
                stmt.setString(3, node.name)
                stmt.setString(4, node.qualifiedName)
                stmt.setString(5, node.filePath)
                stmt.setString(6, node.language)
                stmt.setInt(7, node.startLine)
                stmt.setInt(8, node.endLine)
                stmt.setInt(9, node.startColumn)
                stmt.setInt(10, node.endColumn)
                stmt.setString(11, node.docstring)
                stmt.setString(12, node.signature)
                stmt.setString(13, node.visibility)
                stmt.setInt(14, if (node.isExported) 1 else 0)
                stmt.setInt(15, if (node.isAsync) 1 else 0)
                stmt.setInt(16, if (node.isStatic) 1 else 0)
                stmt.setInt(17, if (node.isAbstract) 1 else 0)
                stmt.setString(18, node.decorators)
                stmt.setString(19, node.typeParameters)
                stmt.setLong(20, node.updatedAt)
                stmt.addBatch()
                batchCount++
                if (batchCount >= BATCH_SIZE) {
                    stmt.executeBatch()
                    written += batchCount
                    onProgress?.invoke(written, totalItems)
                    batchCount = 0
                }
            }
            if (batchCount > 0) {
                stmt.executeBatch()
                written += batchCount
                onProgress?.invoke(written, totalItems)
            }
        }

        // 3. 写入 edges
        conn.prepareStatement(
            """
            INSERT INTO edges
            (source, target, kind, metadata, line, col, provenance)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            var batchCount = 0
            for (edge in result.edges) {
                stmt.setString(1, edge.source)
                stmt.setString(2, edge.target)
                stmt.setString(3, edge.kind)
                stmt.setString(4, edge.metadata)
                stmt.setObject(5, edge.line)
                stmt.setObject(6, edge.col)
                stmt.setString(7, edge.provenance)
                stmt.addBatch()
                batchCount++
                if (batchCount >= BATCH_SIZE) {
                    stmt.executeBatch()
                    written += batchCount
                    onProgress?.invoke(written, totalItems)
                    batchCount = 0
                }
            }
            if (batchCount > 0) {
                stmt.executeBatch()
                written += batchCount
                onProgress?.invoke(written, totalItems)
            }
        }

        conn.commit()

        // 4. 运行维护优化（必须在 autoCommit 模式下）
        conn.autoCommit = true
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA optimize")
        }
        conn.autoCommit = false
    }

    /**
     * 获取数据库中已有源码节点的 qualified_name（非 jar: 开头）
     */
    fun getSourceQualifiedNames(): Set<String> {
        val conn = connection ?: throw IllegalStateException("Database not opened")
        val result = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT qualified_name FROM nodes WHERE file_path NOT LIKE 'jar:%'").use { rs ->
                while (rs.next()) {
                    result.add(rs.getString(1))
                }
            }
        }
        return result
    }

    /**
     * 统计信息
     */
    fun getStats(): GraphStats {
        val conn = connection ?: throw IllegalStateException("Database not opened")

        val nodeCount = queryLong(conn, "SELECT COUNT(*) FROM nodes")
        val edgeCount = queryLong(conn, "SELECT COUNT(*) FROM edges")
        val fileCount = queryLong(conn, "SELECT COUNT(*) FROM files")

        val nodesByKind = mutableMapOf<String, Long>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT kind, COUNT(*) FROM nodes GROUP BY kind").use { rs ->
                while (rs.next()) {
                    nodesByKind[rs.getString(1)] = rs.getLong(2)
                }
            }
        }

        val edgesByKind = mutableMapOf<String, Long>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT kind, COUNT(*) FROM edges GROUP BY kind").use { rs ->
                while (rs.next()) {
                    edgesByKind[rs.getString(1)] = rs.getLong(2)
                }
            }
        }

        return GraphStats(
            nodeCount = nodeCount,
            edgeCount = edgeCount,
            fileCount = fileCount,
            nodesByKind = nodesByKind,
            edgesByKind = edgesByKind
        )
    }

    private fun initSchema() {
        connection?.createStatement()?.use { stmt ->
            for (sql in CODEGRAPH_SCHEMA) {
                stmt.execute(sql)
            }
            // 标记所有迁移已应用：schema.sql 已包含 version 4 的完整结构
            val now = System.currentTimeMillis()
            stmt.execute(
                "INSERT OR IGNORE INTO schema_versions (version, applied_at, description) VALUES (2, $now, 'Add project metadata, provenance tracking, and unresolved ref context')"
            )
            stmt.execute(
                "INSERT OR IGNORE INTO schema_versions (version, applied_at, description) VALUES (3, $now, 'Add lower(name) expression index for memory-efficient case-insensitive lookups')"
            )
            stmt.execute(
                "INSERT OR IGNORE INTO schema_versions (version, applied_at, description) VALUES (4, $now, 'Drop redundant idx_edges_source / idx_edges_target (covered by source_kind / target_kind composites)')"
            )
        }
    }

    companion object {
        private val CODEGRAPH_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS schema_versions (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL, description TEXT)",
            "INSERT OR IGNORE INTO schema_versions (version, applied_at, description) VALUES (1, strftime('%s', 'now') * 1000, 'Initial schema')",
            "CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, qualified_name TEXT NOT NULL, file_path TEXT NOT NULL, language TEXT NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, start_column INTEGER NOT NULL, end_column INTEGER NOT NULL, docstring TEXT, signature TEXT, visibility TEXT, is_exported INTEGER DEFAULT 0, is_async INTEGER DEFAULT 0, is_static INTEGER DEFAULT 0, is_abstract INTEGER DEFAULT 0, decorators TEXT, type_parameters TEXT, updated_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS edges (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, target TEXT NOT NULL, kind TEXT NOT NULL, metadata TEXT, line INTEGER, col INTEGER, provenance TEXT DEFAULT NULL, FOREIGN KEY (source) REFERENCES nodes(id) ON DELETE CASCADE, FOREIGN KEY (target) REFERENCES nodes(id) ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS files (path TEXT PRIMARY KEY, content_hash TEXT NOT NULL, language TEXT NOT NULL, size INTEGER NOT NULL, modified_at INTEGER NOT NULL, indexed_at INTEGER NOT NULL, node_count INTEGER DEFAULT 0, errors TEXT)",
            "CREATE TABLE IF NOT EXISTS unresolved_refs (id INTEGER PRIMARY KEY AUTOINCREMENT, from_node_id TEXT NOT NULL, reference_name TEXT NOT NULL, reference_kind TEXT NOT NULL, line INTEGER NOT NULL, col INTEGER NOT NULL, candidates TEXT, file_path TEXT NOT NULL DEFAULT '', language TEXT NOT NULL DEFAULT 'unknown', FOREIGN KEY (from_node_id) REFERENCES nodes(id) ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_kind ON nodes(kind)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_name ON nodes(name)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_qualified_name ON nodes(qualified_name)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_file_path ON nodes(file_path)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_language ON nodes(language)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_file_line ON nodes(file_path, start_line)",
            "CREATE INDEX IF NOT EXISTS idx_nodes_lower_name ON nodes(lower(name))",
            "CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(id, name, qualified_name, docstring, signature, content='nodes', content_rowid='rowid')",
            "CREATE TRIGGER IF NOT EXISTS nodes_ai AFTER INSERT ON nodes BEGIN INSERT INTO nodes_fts(rowid, id, name, qualified_name, docstring, signature) VALUES (NEW.rowid, NEW.id, NEW.name, NEW.qualified_name, NEW.docstring, NEW.signature); END",
            "CREATE TRIGGER IF NOT EXISTS nodes_ad AFTER DELETE ON nodes BEGIN INSERT INTO nodes_fts(nodes_fts, rowid, id, name, qualified_name, docstring, signature) VALUES ('delete', OLD.rowid, OLD.id, OLD.name, OLD.qualified_name, OLD.docstring, OLD.signature); END",
            "CREATE TRIGGER IF NOT EXISTS nodes_au AFTER UPDATE ON nodes BEGIN INSERT INTO nodes_fts(nodes_fts, rowid, id, name, qualified_name, docstring, signature) VALUES ('delete', OLD.rowid, OLD.id, OLD.name, OLD.qualified_name, OLD.docstring, OLD.signature); INSERT INTO nodes_fts(rowid, id, name, qualified_name, docstring, signature) VALUES (NEW.rowid, NEW.id, NEW.name, NEW.qualified_name, NEW.docstring, NEW.signature); END",
            "CREATE INDEX IF NOT EXISTS idx_edges_kind ON edges(kind)",
            "CREATE INDEX IF NOT EXISTS idx_edges_source_kind ON edges(source, kind)",
            "CREATE INDEX IF NOT EXISTS idx_edges_target_kind ON edges(target, kind)",
            "CREATE INDEX IF NOT EXISTS idx_files_language ON files(language)",
            "CREATE INDEX IF NOT EXISTS idx_files_modified_at ON files(modified_at)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_from_node ON unresolved_refs(from_node_id)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_name ON unresolved_refs(reference_name)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_file_path ON unresolved_refs(file_path)",
            "CREATE INDEX IF NOT EXISTS idx_unresolved_from_name ON unresolved_refs(from_node_id, reference_name)",
            "CREATE INDEX IF NOT EXISTS idx_edges_provenance ON edges(provenance)",
            "CREATE TABLE IF NOT EXISTS project_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
    }

    private fun queryLong(conn: Connection, sql: String): Long {
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }
}

data class GraphStats(
    val nodeCount: Long,
    val edgeCount: Long,
    val fileCount: Long,
    val nodesByKind: Map<String, Long>,
    val edgesByKind: Map<String, Long>
)
