package com.jargraph.config

import java.io.File
import java.util.Properties

class GraphConfig(
    var projectPath: String = ".",
    var classpath: String = "",
    var excludedJars: List<String> = emptyList()
) {
    companion object {
        const val CONFIG_PATH = ".jar-graph/config.properties"

        @JvmStatic
        fun load(): GraphConfig {
            val file = File(CONFIG_PATH)
            return if (file.exists()) {
                val props = Properties().apply { load(file.reader()) }
                GraphConfig(
                    projectPath = props.getProperty("project.path", "."),
                    classpath = props.getProperty("project.classpath", ""),
                    excludedJars = props.getProperty("project.excluded.jars", "")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                )
            } else {
                GraphConfig()
            }
        }

        @JvmStatic
        fun save(config: GraphConfig, rootDir: File = File(".")) {
            val file = File(rootDir, CONFIG_PATH)
            file.parentFile?.mkdirs()
            val props = Properties().apply {
                setProperty("project.path", config.projectPath)
                setProperty("project.classpath", config.classpath)
                setProperty("project.excluded.jars", config.excludedJars.joinToString(","))
            }
            props.store(file.writer(), "JAR Graph Configuration")
        }
    }
}
