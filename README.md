# jargraph

JAR 字节码分析器，生成 [codegraph](https://github.com/colbymchenry/codegraph) 兼容的 SQLite 知识图谱，让 AI 助手（MCP）也能导航项目依赖。

```bash
jargraph init   # 解析 Maven 依赖，交互式选择要索引的 JAR
jargraph index  # 分析字节码，写入 .codegraph/codegraph.db
```

## 安装

```bash
npm install -g https://github.com/starrylistener/jar-graph/releases/download/0.1.0/jargraph-cli-0.1.0.tgz
```

要求：
- Node.js >= 18
- Java 17+
- Maven 3.6+（或项目根目录有 `mvnw`）

## 快速开始

```bash
cd your-maven-project

# 1. 初始化：解析 pom.xml，弹出页面选择核心依赖
jargraph init

# 2. 索引：分析选中的 JAR 字节码
jargraph index

# 3. 用 codegraph MCP 读取
npx @anthropic-ai/codegraph serve --mcp
```

## 命令

### `jargraph init`

从当前目录向上查找 `pom.xml`，解析传递依赖，启动本地页面让你勾选要索引的 JAR。

- 包含 `hzero` 的 JAR 标记为 **平台**，默认勾选
- 包含 `tarzan` 的 JAR 标记为 **自研**，默认勾选
- 其他第三方依赖默认不勾选，可自行勾选

选项：

| 选项 | 说明 |
|------|------|
| `-f, --force` | 强制重新初始化，覆盖已有配置 |
| `--skip-select` | 跳过选择页面，默认全部索引 |

配置保存在 `.jar-graph/config.properties`。

### `jargraph index`

读取 `init` 配置，分析选中的 JAR 并写入数据库。

| 选项 | 说明 |
|------|------|
| `-o, --output` | 输出数据库路径，默认 `.codegraph/codegraph.db` |
| `--clear` | 清空已有数据库后重建 |
| `--exclude-covered` | 跳过已被项目源码覆盖的类 |

也可直接指定 JAR 路径，不走配置：

```bash
jargraph index lib/a.jar lib/b.jar -o out.db
```

## 数据结构

生成的 SQLite 数据库兼容 codegraph schema：

- `nodes` — 类、方法、字段节点
- `edges` — extends / implements / contains / calls / references
- `files` — JAR 文件记录
- `nodes_fts` — FTS5 全文索引

重复执行 `jargraph index` 会自动清理已有的 JAR 数据，不会累积重复边。

## 技术栈

- Kotlin / JVM 17
- ASM 9 字节码分析
- sqlite-jdbc + WAL 模式
- picocli 命令行框架
- Node.js launcher（跨平台 Java / Maven 探测）

## License

MIT
