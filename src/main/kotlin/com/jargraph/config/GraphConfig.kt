package com.jargraph.config

import java.io.File
import java.util.Properties

/**
 * 知识图谱全局配置（简化版，properties 格式）
 */
class GraphConfig(
    var scope: Scope = Scope.PROJECT,
    var projectRoot: String = ".",
    var m2Repo: String = "~/.m2/repository",
    var maxSizeGb: Int = 5,
    var forceScan: Boolean = false
) {
    companion object {
        private const val CONFIG_PATH = ".codegraph/config.properties"

        @JvmStatic
        fun load(): GraphConfig {
            val file = File(CONFIG_PATH)
            return if (file.exists()) {
                val props = Properties().apply { load(file.reader()) }
                GraphConfig(
                    scope = Scope.valueOf(props.getProperty("scope", "PROJECT")),
                    projectRoot = props.getProperty("project.root", "."),
                    m2Repo = props.getProperty("global.m2Repo", "~/.m2/repository"),
                    maxSizeGb = props.getProperty("global.maxSizeGb", "5").toInt(),
                    forceScan = props.getProperty("global.forceScan", "false").toBoolean()
                )
            } else {
                GraphConfig()
            }
        }

        @JvmStatic
        fun save(config: GraphConfig) {
            val file = File(CONFIG_PATH)
            file.parentFile?.mkdirs()
            val props = Properties().apply {
                setProperty("scope", config.scope.name)
                setProperty("project.root", config.projectRoot)
                setProperty("global.m2Repo", config.m2Repo)
                setProperty("global.maxSizeGb", config.maxSizeGb.toString())
                setProperty("global.forceScan", config.forceScan.toString())
            }
            props.store(file.writer(), "JAR Graph Configuration")
        }
    }
}

enum class Scope {
    PROJECT, GLOBAL, CUSTOM
}
