package com.jargraph.cli

import com.jargraph.config.GraphConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 本地 HTTP 服务器，提供 JAR 勾选页面
 * 按 Maven groupId 分组展示，支持折叠/搜索/组内全选
 */
object JarSelectorServer {

    fun startAndWait(jars: List<String>, projectRoot: File, config: GraphConfig): List<String> {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val port = server.address.port
        val url = "http://localhost:$port"

        var excludedResult: List<String>? = null

        server.createContext("/") { exchange ->
            handleIndex(exchange, jars)
        }
        server.createContext("/save") { exchange ->
            excludedResult = handleSave(exchange, exchange.requestBody.reader().readText())
        }
        server.createContext("/static/style.css") { exchange ->
            serveCss(exchange)
        }

        server.executor = null
        server.start()

        println("[INFO] 请在浏览器中打开: $url")
        tryOpenBrowser(url)

        // 阻塞等待用户提交
        while (excludedResult == null) {
            Thread.sleep(200)
        }

        server.stop(0)
        return excludedResult!!
    }

    private fun handleIndex(exchange: HttpExchange, jars: List<String>) {
        val html = buildHtml(jars)
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }

    private fun handleSave(exchange: HttpExchange, body: String): List<String> {
        val params = parseFormBody(body)
        val excluded = params["excluded"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val response = "{\"success\": true, \"excluded\": ${excluded.size}}"
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
        return excluded
    }

    private fun serveCss(exchange: HttpExchange) {
        val css = buildCss()
        val bytes = css.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/css; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }

    private fun parseFormBody(body: String): Map<String, String> {
        return body.split("&").mapNotNull { part ->
            val idx = part.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
                key to value
            } else null
        }.toMap()
    }

    private fun tryOpenBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Exception) {}

        val os = System.getProperty("os.name").lowercase()
        try {
            when {
                os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", url))
                os.contains("win") -> Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", url))
                else -> Runtime.getRuntime().exec(arrayOf("xdg-open", url))
            }
        } catch (_: Exception) {}
    }

    /** JAR 标签映射：文件名包含关键字 -> (标签文字, CSS类名) */
    private fun getJarBadge(name: String): Pair<String, String>? {
        val lower = name.lowercase()
        return when {
            lower.contains("hzero") -> "平台" to "badge-platform"
            lower.contains("tarzan") -> "自研" to "badge-custom"
            else -> null
        }
    }

    private fun isPriorityJar(name: String): Boolean = getJarBadge(name) != null

    private fun buildHtml(jars: List<String>): String {
        val sortedJars = jars.sortedWith(compareByDescending<String> { isPriorityJar(File(it).name) }.thenBy { File(it).name })

        val jarItems = sortedJars.mapIndexed { index, jarPath ->
            val file = File(jarPath)
            val name = file.name
            val size = formatBytes(file.length())
            val path = jarPath.replace("\\", "\\\\").replace("\"", "\\\"")
            val nameEscaped = name.replace("\"", "\\\"")
            val badgeInfo = getJarBadge(name)
            val badge = badgeInfo?.let { (text, clazz) -> """<span class="badge $clazz">$text</span>""" } ?: ""
            val clazz = when (badgeInfo?.second) {
                "badge-platform" -> "jar-item platform"
                "badge-custom" -> "jar-item custom"
                else -> "jar-item"
            }
            val checkedAttr = if (badgeInfo != null) "checked" else ""
            val priorityAttr = if (badgeInfo != null) "data-priority=\"true\"" else ""
            """
            <label class="$clazz">
                <input type="checkbox" name="jar" value="$path" $checkedAttr $priorityAttr data-name="$nameEscaped">
                <span class="jar-name">$nameEscaped $badge</span>
                <span class="jar-size">$size</span>
                <span class="jar-path">$path</span>
            </label>
            """.trimIndent()
        }.joinToString("\n")

        return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>JAR Graph - 选择要索引的依赖</title>
            <link rel="stylesheet" href="/static/style.css">
        </head>
        <body>
            <div class="container">
                <h1>选择要索引的 JAR 依赖</h1>
                <p class="subtitle">共 ${jars.size} 个 JAR，取消勾选可跳过不需要索引的包。<span class="badge-inline badge-platform">平台</span> 为核心平台依赖，<span class="badge-inline badge-custom">自研</span> 为项目自研模块。</p>

                <div class="toolbar">
                    <input type="text" id="search" placeholder="搜索 JAR 名称..." autocomplete="off">
                    <div class="actions">
                        <button id="selectAll">全选</button>
                        <button id="selectNone">全不选</button>
                        <button id="invert">反选</button>
                        <button id="selectPriority">推荐</button>
                    </div>
                </div>

                <div class="stats" id="stats">已选择 ${sortedJars.count { isPriorityJar(File(it).name) }} / ${jars.size}</div>

                <form id="jarForm">
                    <div class="jar-list" id="jarList">
                        $jarItems
                    </div>
                    <div class="footer">
                        <button type="submit" class="submit-btn">确认选择并开始索引</button>
                    </div>
                </form>
            </div>

            <script>
                const form = document.getElementById('jarForm');
                const jarList = document.getElementById('jarList');
                const searchInput = document.getElementById('search');
                const stats = document.getElementById('stats');
                let allItems = Array.from(document.querySelectorAll('.jar-item'));

                function updateStats() {
                    const checked = document.querySelectorAll('input[name="jar"]:checked').length;
                    const total = document.querySelectorAll('input[name="jar"]').length;
                    stats.textContent = '已选择 ' + checked + ' / ' + total;
                }

                function getExcluded() {
                    const unchecked = Array.from(document.querySelectorAll('input[name="jar"]:not(:checked)'));
                    return unchecked.map(cb => cb.value).join(',');
                }

                jarList.addEventListener('change', updateStats);

                document.getElementById('selectAll').addEventListener('click', () => {
                    document.querySelectorAll('.jar-item:not([style*="display"]) input[name="jar"]').forEach(cb => cb.checked = true);
                    updateStats();
                });
                document.getElementById('selectNone').addEventListener('click', () => {
                    document.querySelectorAll('.jar-item:not([style*="display"]) input[name="jar"]').forEach(cb => cb.checked = false);
                    updateStats();
                });
                document.getElementById('invert').addEventListener('click', () => {
                    document.querySelectorAll('.jar-item:not([style*="display"]) input[name="jar"]').forEach(cb => cb.checked = !cb.checked);
                    updateStats();
                });
                document.getElementById('selectPriority').addEventListener('click', () => {
                    document.querySelectorAll('.jar-item:not([style*="display"]) input[name="jar"]').forEach(cb => cb.checked = cb.hasAttribute('data-priority'));
                    updateStats();
                });

                searchInput.addEventListener('input', (e) => {
                    const q = e.target.value.toLowerCase();
                    allItems.forEach(item => {
                        const name = item.querySelector('.jar-name').textContent.toLowerCase();
                        item.style.display = name.includes(q) ? '' : 'none';
                    });
                });

                form.addEventListener('submit', async (e) => {
                    e.preventDefault();
                    const excluded = getExcluded();
                    const btn = form.querySelector('.submit-btn');
                    btn.disabled = true;
                    btn.textContent = '保存中...';
                    try {
                        await fetch('/save', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                            body: 'excluded=' + encodeURIComponent(excluded)
                        });
                        btn.textContent = '已保存，可以关闭此页面';
                        btn.style.background = '#22c55e';
                    } catch (err) {
                        btn.textContent = '保存失败: ' + err.message;
                        btn.disabled = false;
                    }
                });

                updateStats();
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun buildCss(): String {
        return """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: #f5f5f5;
            color: #333;
            line-height: 1.5;
        }
        .container {
            max-width: 960px;
            margin: 40px auto;
            padding: 0 20px;
        }
        h1 {
            font-size: 24px;
            margin-bottom: 8px;
        }
        .subtitle {
            color: #666;
            margin-bottom: 24px;
        }
        .toolbar {
            display: flex;
            gap: 12px;
            margin-bottom: 16px;
            flex-wrap: wrap;
        }
        .toolbar input {
            flex: 1;
            min-width: 200px;
            padding: 10px 14px;
            border: 1px solid #ddd;
            border-radius: 6px;
            font-size: 14px;
        }
        .actions {
            display: flex;
            gap: 8px;
        }
        .actions button {
            padding: 8px 16px;
            border: 1px solid #ddd;
            background: #fff;
            border-radius: 6px;
            cursor: pointer;
            font-size: 13px;
        }
        .actions button:hover {
            background: #f0f0f0;
        }
        .stats {
            margin-bottom: 12px;
            font-size: 14px;
            color: #555;
        }
        .jar-list {
            background: #fff;
            border: 1px solid #ddd;
            border-radius: 8px;
            max-height: 60vh;
            overflow-y: auto;
        }
        .jar-item {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 10px 16px;
            border-bottom: 1px solid #eee;
            cursor: pointer;
            transition: background 0.1s;
        }
        .jar-item:hover {
            background: #f8f9fa;
        }
        .jar-item:last-child {
            border-bottom: none;
        }
        .jar-item input[type="checkbox"] {
            width: 18px;
            height: 18px;
            flex-shrink: 0;
        }
        .jar-name {
            font-weight: 500;
            min-width: 200px;
            flex-shrink: 0;
            display: flex;
            align-items: center;
            gap: 6px;
        }
        .jar-size {
            color: #888;
            font-size: 12px;
            width: 70px;
            text-align: right;
            flex-shrink: 0;
        }
        .jar-path {
            color: #999;
            font-size: 12px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            flex: 1;
        }
        .badge {
            display: inline-block;
            padding: 1px 6px;
            font-size: 11px;
            font-weight: 600;
            border-radius: 4px;
            line-height: 1.4;
        }
        .badge-platform {
            background: #dbeafe;
            color: #1d4ed8;
        }
        .badge-custom {
            background: #dcfce7;
            color: #15803d;
        }
        .badge-inline {
            display: inline-block;
            padding: 1px 6px;
            font-size: 12px;
            font-weight: 600;
            border-radius: 4px;
        }
        .jar-item.platform {
            background: #f0f7ff;
        }
        .jar-item.platform:hover {
            background: #e0efff;
        }
        .jar-item.custom {
            background: #f0fdf4;
        }
        .jar-item.custom:hover {
            background: #dcfce7;
        }
        .footer {
            margin-top: 24px;
            text-align: center;
        }
        .submit-btn {
            padding: 12px 32px;
            background: #2563eb;
            color: #fff;
            border: none;
            border-radius: 8px;
            font-size: 15px;
            cursor: pointer;
        }
        .submit-btn:hover {
            background: #1d4ed8;
        }
        .submit-btn:disabled {
            opacity: 0.7;
            cursor: not-allowed;
        }
        """.trimIndent()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
        return "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
