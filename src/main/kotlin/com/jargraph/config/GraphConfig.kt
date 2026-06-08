package com.jargraph.config

import java.io.File
import java.util.Properties

class GraphConfig(
    var projectPath: String = ".",
    var classpath: String = ""
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
                    classpath = props.getProperty("project.classpath", "")
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
                setProperty("project.path", config.projectPath)
                setProperty("project.classpath", config.classpath)
            }
            props.store(file.writer(), "JAR Graph Configuration")
        }
    }
}
