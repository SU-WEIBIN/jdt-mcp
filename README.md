# JDT MCP

面向 AI 的 Java/Maven 项目和 JAR 代码导航 MCP 服务。

本服务把 Maven 项目模型、JDT 源码绑定分析、JAR 字节码索引和按需反编译能力，通过 MCP stdio 提供给 AI。一个 MCP 进程只管理一个 Maven 项目；项目路径在进程启动时确定，不能通过 MCP 工具切换。

本文前半部分是给 AI/MCP 客户端的调用指南，后半部分是运行、缓存和开发说明。AI 客户端应优先阅读“AI 调用契约”“工具选择”和“稳定调用规则”。

## AI 调用契约

### 服务边界

- 这是只读的 Java 代码导航服务，不修改源码，不自动重构，不执行测试，不执行 Maven 构建。
- 一个进程对应一个 Maven 项目，必须在启动命令中提供项目根目录。
- 服务主要分析项目的 main Java 源码、Maven 依赖 JAR 和配置中的额外本地 JAR。
- 服务做的是静态分析，不等价于运行时调用跟踪。
- MCP 使用 stdio JSON-RPC。stdout 只能出现协议响应；启动日志和错误日志写入 stderr。

### 初始化顺序

MCP 客户端建议按下面顺序工作：

1. 发送 `initialize`，协议版本使用 `2024-11-05`。
2. 发送 `notifications/initialized` 通知。
3. 可选：调用 `tools/list` 获取当前工具列表。
4. 调用 `index_status`。
5. 当状态为 `READY` 或 `DEGRADED` 后再开始依赖完整索引的查询。
6. 先用 `search_symbols` 找到准确的类或方法，再把返回的 `id` 传给后续工具。

初始化请求示例：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ai-client","version":"1.0"}}}
```

状态查询示例：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"index_status","arguments":{}}}
```

`tools/call` 的业务参数必须放在 `params.arguments` 对象中。没有参数时传空对象 `{}`。

### 解析工具返回值

本实现把工具结果编码成 MCP text content，因此客户端需要再解析一次 JSON：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"state\":\"READY\",\"indexedArtifacts\":12,\"warnings\":[]}"
      }
    ],
    "isError": false
  }
}
```

正确处理方式是：

1. 读取 `result.content[0].text`。
2. 将这个字符串再次解析为 JSON 对象。
3. 根据业务对象中的 `found`、`state`、`warnings`、`count` 和 `results` 字段继续处理。

JSON-RPC 层错误会出现在 `error.code` 和 `error.message` 中。工具业务结果中的 `isError` 当前正常返回 `false`。

### 索引状态决策

| `state` | AI 应如何处理 |
| --- | --- |
| `INDEXING` | 等待后重复调用 `index_status`；此时查询可能不完整。 |
| `READY` | 可以进行完整查询。 |
| `DEGRADED` | 可以查询，但必须阅读 `warnings`、`mavenDiagnosticSummary`，并在回答中说明可能缺失。 |

`index_status` 的重要字段：

| 字段 | 含义 |
| --- | --- |
| `indexedArtifacts` | 已有可用字节码快照的 JAR 数量。 |
| `declaredArtifacts` | 当前 Maven 模型解析出的 artifact 数量。 |
| `projectClasses` / `projectMethods` | 项目源码索引中的类型和方法数量。 |
| `bytecodeClasses` / `bytecodeMethods` | 已索引 JAR 字节码中的类型和方法数量。 |
| `indexedClasses` / `indexedMethods` | 项目源码与 JAR 字节码的合计数量。 |
| `callEdges` | 项目源码和 JAR 字节码调用边的合计数量。 |
| `warnings` | 缺失 JAR、解析失败、缓存恢复信息和其他诊断。 |
| `mavenResolutionState` | Maven 模型解析状态。 |
| `mavenDiagnosticSummary` | Maven 依赖解析诊断摘要。 |

以 `[INFO/source-cache]` 或 `[INFO/bytecode-cache]` 开头的 warning 通常是缓存复用信息，不代表索引失败。其他 warning 需要结合结果谨慎解释。

## 工具选择

| 用户问题 | 首选工具 | 下一步 |
| --- | --- | --- |
| 找类或方法 | `search_symbols` | 取结果中的 `id`，再调用 `get_class` 或 `get_method`。 |
| 搜索源码文本 | `search_text` | 根据 `file` 和 `line` 阅读源码位置。 |
| 查看类代码 | `get_class` | 项目类返回源码；JAR 类可能触发 CFR 反编译。 |
| 查看方法代码 | `get_method` | 项目方法返回源码片段；JAR 方法返回所属反编译类。 |
| 查看某个 JAR 的类 | `inspect_jar` | 优先传精确 Maven `coordinate`，再用 `query` 过滤类名。 |
| 项目哪里调用了某个 API | `find_project_usages` | 传搜索结果中的方法 `symbolId`，并按 `caller.source` 过滤项目调用者。 |
| 谁调用了这个方法 | `find_callers` | 传方法 `symbolId`。结果可能来自项目和 JAR。 |
| 一个方法直接调用了什么 | `find_callees` | 传方法 `symbolId`。 |
| 继续追踪调用链 | `trace_call_chain` | 传起点方法 `symbolId`，设置 `maxDepth` 和 `maxResults`。 |
| 查看项目和缓存位置 | `project_info` | 不需要参数。 |
| 查看索引是否可用 | `index_status` | 不需要参数。 |

## 稳定调用规则

### 先搜索，再复用 ID

不要让 AI 猜 `symbolId` 或 `id`。推荐流程是：

```text
search_symbols -> 选择精确结果 -> 复用结果中的 id
                         |
                         +-> get_class / get_method
                         +-> find_project_usages / find_callers
                         +-> find_callees
                         +-> trace_call_chain
```

关系查询优先使用稳定 ID，只有没有 ID 时才使用 `query`。使用 `query` 时，服务会取搜索结果中的第一个匹配项；重载方法或同名类可能因此选错。

方法 ID 使用 JVM descriptor 区分重载。例如：

```text
method:org/eclipse/jdt/core/dom/ASTParser#createAST(Lorg/eclipse/core/runtime/IProgressMonitor;)Lorg/eclipse/jdt/core/dom/ASTNode;
```

常见符号字段：

| 字段 | 含义 |
| --- | --- |
| `id` | 后续查询使用的稳定符号 ID。 |
| `kind` | `TYPE` 或 `METHOD`。 |
| `name` | 简单类名或方法名。 |
| `qualifiedName` | 全限定名或带方法参数/返回值的签名。 |
| `signature` | 用于搜索和展示的签名。 |
| `source` | 通常为 `project`、`bytecode` 或 JDT 绑定产生的 `dependency`。 |
| `module` | 项目模块信息或 Maven artifact 坐标。 |
| `file` | 源码文件或 JAR 文件路径。 |
| `startLine` / `endLine` | 源码符号的行范围；字节码符号通常为 `0`。 |
| `declaringTypeId` | 所属类型 ID（如果可用）。 |

`source: dependency` 可能只是源码绑定产生的依赖占位符，不一定包含可直接反编译的 JAR 文件信息。需要查看第三方 JAR 代码时，优先从 `source: bytecode` 的搜索结果取得 ID。

### 控制结果规模

- `maxResults` 默认来自配置，默认值为 `100`；传入值至少按 `1` 处理。
- `maxDepth` 默认来自配置，默认值为 `8`；只对 `trace_call_chain` 生效。
- 查询结果可能因为数量上限而不完整。调用链还会进行循环检测。
- `search_symbols` 是大小写不敏感的子串匹配，不是正则表达式。
- `search_text` 默认大小写不敏感；`path` 是文件路径子串过滤器，不是 glob。
- 不要一次请求过大的调用链；先用较小的 `maxDepth` 和 `maxResults`，再逐步扩大。

## 工具参数和返回值

### `project_info`

用途：查看当前进程绑定的项目、Maven 模型和缓存路径。

参数：

```json
{}
```

返回对象包含 `projectId`、`projectRoot`、`projectCacheRoot`、`workspaceRoot`、`indexRoot`、`decompileRoot`、Maven 信息、额外 JAR、当前 `state` 和 `indexWarnings`。

### `index_status`

用途：查看索引是否完成，以及项目/JAR 的统计信息。

参数：

```json
{}
```

索引未完成时不要把空结果当成“项目中不存在该类或方法”。

### `search_symbols`

用途：按类名、方法名、全限定名或签名搜索项目源码和已持久化的 JAR 字节码索引。

参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `query` | string | 建议填写类名、方法名或签名片段；空字符串会匹配任意符号。 |
| `kind` | string | 可选，`TYPE` 或 `METHOD`。 |
| `maxResults` | integer | 可选，默认 `100`。 |

示例：

```json
{"query":"ASTParser","kind":"TYPE","maxResults":10}
```

返回：

```json
{"query":"ASTParser","count":1,"results":[
  {
    "id":"type:org/eclipse/jdt/core/dom/ASTParser",
    "kind":"TYPE",
    "name":"ASTParser",
    "qualifiedName":"org.eclipse.jdt.core.dom.ASTParser",
    "signature":"org.eclipse.jdt.core.dom.ASTParser",
    "source":"bytecode",
    "module":"org.eclipse.jdt:jdt-core:...",
    "file":".../jdt-core-....jar",
    "startLine":0,
    "endLine":0,
    "declaringTypeId":null
  }
]}
```

项目源码搜索结果和 JAR 字节码搜索结果混合返回；项目源码结果通常排在前面。返回数量由 `maxResults` 限制。

### `search_text`

用途：在项目 main Java 源码中按文本查找调用、配置或实现位置。

参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `query` | string | 必填，不能为空。 |
| `path` | string | 可选，文件路径子串，例如 `src/main/java`。 |
| `caseSensitive` | boolean | 可选，默认 `false`。 |
| `maxResults` | integer | 可选，默认 `100`。 |

示例：

```json
{"query":"createAST","path":"src/main/java","caseSensitive":false,"maxResults":20}
```

返回对象形如：

```json
{
  "query":"createAST",
  "count":1,
  "truncated":false,
  "results":[
    {
      "file":"C:/work/app/src/main/java/example/App.java",
      "module":"example:app:1.0.0",
      "line":42,
      "text":"ASTParser parser = ASTParser.newParser(...);"
    }
  ]
}
```

当前只扫描 main Java 源码，不扫描测试源码和 generated sources。

### `get_class`

用途：取得一个类型的索引信息和代码。

参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 推荐，直接使用 `search_symbols` 返回的 `TYPE` 结果 ID。 |
| `query` | string | 没有 `id` 时使用，服务取第一个 `TYPE` 匹配项。 |

示例：

```json
{"id":"type:org/eclipse/jdt/core/dom/ASTParser"}
```

找到时返回 `found:true`、符号字段以及 `sourceText`。项目源码类的 `sourceText` 来自原文件；JAR 类第一次查看时可能触发 CFR 反编译，并附带：

- `decompiledFile`：持久化的反编译 Java 文件。
- `decompiler`：使用的反编译器名称。
- `sourceText`：受 `maxResponseBytes` 限制的反编译文本。
- `decompileError`：反编译失败时的错误信息。

找不到时通常返回：

```json
{"found":false,"results":[]}
```

### `get_method`

用途：取得一个方法的索引信息和代码。

参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 推荐，直接使用 `search_symbols` 返回的 `METHOD` 结果 ID。 |
| `query` | string | 没有 `id` 时使用，服务取第一个 `METHOD` 匹配项。 |

示例：

```json
{"id":"method:org/eclipse/jdt/core/dom/ASTParser#createAST(Lorg/eclipse/core/runtime/IProgressMonitor;)Lorg/eclipse/jdt/core/dom/ASTNode;"}
```

项目方法返回源码片段和位置。JAR 方法返回所属反编译类的代码，不能保证只截取该方法本身。

### `inspect_jar`

用途：查看一个已解析 Maven artifact 的类列表和索引统计。

建议始终传精确 `coordinate`：

```json
{
  "coordinate":"org.eclipse.jdt:jdt-core:3.38.0",
  "query":"ASTParser",
  "maxResults":20
}
```

参数规则：

- `coordinate` 是精确的 Maven 坐标，通常为 `groupId:artifactId:version`。
- `query` 在提供 `coordinate` 时用于过滤类名。
- 如果不提供 `coordinate`，`query` 会先作为坐标片段寻找第一个 artifact；这种模式不适合同时做类名过滤，AI 应尽量避免。
- 返回 `found:false` 表示坐标不存在或未被当前 Maven 模型加载。

返回对象形如：

```json
{
  "found":true,
  "artifact":{"coordinate":"org.eclipse.jdt:jdt-core:3.38.0", "...":"..."},
  "classCount":1234,
  "classes":[/* TYPE 符号 */],
  "truncated":false
}
```

### `find_project_usages`

用途：查找某个方法的调用边，通常用于回答“二开项目哪里调用了这个 JAR API”。

参数：

```json
{"symbolId":"method:com/vendor/Client#execute(Ljava/lang/String;)V","maxResults":50}
```

当前实现会查询项目源码和持久化 JAR 索引中的调用边。若只需要项目源码调用者，请在结果的 `caller.source` 中保留 `project`。

### `find_callers`

用途：查找某个方法的调用者，结果可能来自项目源码或 JAR 字节码。

参数：

```json
{"symbolId":"method:com/vendor/Client#execute(Ljava/lang/String;)V","maxResults":50}
```

`find_project_usages` 和 `find_callers` 当前共享同一查询实现；区别主要是语义命名。需要严格区分来源时，使用返回的 `caller.source`、`caller.module` 和 `caller.file` 过滤。

两者返回对象形如：

```json
{
  "symbolId":"method:com/vendor/Client#execute(Ljava/lang/String;)V",
  "query":null,
  "count":1,
  "results":[
    {
      "callerId":"method:example/App#run()V",
      "targetId":"method:com/vendor/Client#execute(Ljava/lang/String;)V",
      "targetSignature":"com.vendor.Client#execute(java.lang.String):void",
      "resolution":"binding",
      "file":"C:/work/app/src/main/java/example/App.java",
      "line":42,
      "column":15,
      "expression":"execute",
      "caller":{"id":"...","source":"project","...":"..."},
      "target":{"id":"...","source":"bytecode","...":"..."}
    }
  ]
}
```

`resolution` 常见值：

- `binding`：JDT 源码绑定解析。
- `direct-bytecode-call`：JAR 字节码直接调用。
- `interface-dispatch`：接口调用指令或接口分派。
- `invokedynamic`：动态调用指令。
- `unresolved`：静态分析无法确定目标。

### `find_callees`

用途：查找一个项目方法或 JAR 方法的直接被调用方法。

参数：

```json
{"symbolId":"method:example/App#run()V","maxResults":50}
```

返回对象包含 `found`、`caller` 和 `results`。`results` 中是直接调用边，不会自动展开下一层。

### `trace_call_chain`

用途：从一个方法开始，按广度优先方向追踪其向下调用链。

参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `symbolId` | string | 推荐，起点方法 ID。 |
| `query` | string | 没有 ID 时使用，取第一个 `METHOD` 匹配项。 |
| `maxDepth` | integer | 可选，默认 `8`。 |
| `maxResults` | integer | 可选，默认 `100`。 |

示例：

```json
{
  "symbolId":"method:example/App#run()V",
  "maxDepth":4,
  "maxResults":40
}
```

返回对象包含 `found`、`start` 和 `results`。每个结果包含：

- `depth`：从起点开始的边深度。
- `path`：从起点到当前目标的符号 ID 列表。
- `call`：调用边信息。

## 推荐的 AI 工作流

### 场景一：分析二开代码调用了哪个第三方 API

```text
1. index_status
2. search_symbols，搜索项目方法或目标 API，kind 可设为 METHOD
3. 复用结果中的 id
4. find_project_usages，传 symbolId
5. 对返回的 caller.source == "project" 的调用点做解释
6. get_method 或 search_text 查看调用上下文
```

### 场景二：阅读一个第三方 JAR 的实现

```text
1. index_status
2. inspect_jar，传精确 coordinate
3. 用返回的类 id 调用 get_class
4. 用 search_symbols 搜索目标方法
5. 用方法 id 调用 get_method
6. 用 find_callees 或 trace_call_chain 继续分析内部调用
```

### 场景三：回答“谁调用了这个方法”

```text
1. search_symbols，取得唯一或最精确的 METHOD id
2. find_callers，传 symbolId
3. 根据 caller.source、caller.module 和 caller.file 区分项目调用者与 JAR 调用者
4. 需要继续向上分析时，对 caller.id 重复调用 find_callers
```

## 运行和构建

### 运行环境

- Java 17 或更高版本。
- Maven 3.6 或更高版本。
- Maven 本地仓库，默认是 `~/.m2/repository`。
- 项目根目录必须包含 `pom.xml`。

服务不启动 Eclipse IDE，也不要求用户配置 Eclipse workspace。JDT Core 通过 headless `ASTParser` 使用。

### 构建

在 `org.eclipse.jdt.mcp.app` 目录执行：

```powershell
# 可选：显式指定用于构建和运行的 JDK
$env:JDT_MCP_JAVA_HOME = 'C:\path\to\jdk-21'
.\build.ps1
```

构建脚本会读取 POM 中的 `maven.compiler.release`，检查 `JDT_MCP_JAVA_HOME`、`JAVA_HOME`、PATH 和常见 JDK 安装目录，选择兼容版本，设置 Maven 的 `JAVA_HOME`，并核对 `mvn -version` 的 Java home。找不到兼容 JDK 时会直接报告要求的版本和可覆盖的环境变量。

### 启动

```powershell
# 可省略 JDT_MCP_JAVA_HOME，让脚本自动选择兼容 JDK
.\build.ps1 -Run --project 'C:\work\my-maven-project'
```

使用配置文件：

```powershell
.\build.ps1 -Run `
    --project 'C:\work\my-maven-project' `
    --config 'C:\work\jdt-mcp.json'
```

直接启动已构建产物：

```powershell
$javaHome = 'C:\path\to\jdk-21'
$cp = (Join-Path (Get-Location) 'target/classes') + [IO.Path]::PathSeparator + (Get-Content -Raw target/classpath.txt).Trim()
& (Join-Path $javaHome 'bin/java.exe') `
    -cp $cp `
    org.eclipse.jdt.mcp.app.Main `
    --project 'C:\work\my-maven-project'
```

### MCP 客户端配置原则

不同 AI 客户端的配置字段名称不同，但都应满足：

- 传输方式为 stdio。
- 启动命令最终执行 `org.eclipse.jdt.mcp.app.Main` 或 `build.ps1 -Run`。
- 将项目路径放在启动参数 `--project` 中。
- 将配置文件放在启动参数 `--config` 中（如果使用）。
- 不要把日志重定向到 stdout。

## 配置文件

完整示例见：[jdt-mcp.example.json](./jdt-mcp.example.json)。

```json
{
  "projectRoot": "C:/work/my-maven-project",
  "cacheRoot": "C:/jdt-mcp/cache",
  "decompileRoot": "C:/jdt-mcp/decompiled",
  "mavenLocalRepository": "C:/Users/user/.m2/repository",
  "additionalJars": [
    "C:/vendor/lib/vendor-api.jar"
  ],
  "activeProfiles": [],
  "allowNetwork": false,
  "includeTestSources": false,
  "includeGeneratedSources": false,
  "maxCallDepth": 8,
  "maxResults": 100,
  "maxResponseBytes": 1048576
}
```

配置文件中的相对路径相对于配置文件所在目录解析。命令行 `--project` 优先于配置文件中的 `projectRoot`。

| 配置项 | 说明 |
| --- | --- |
| `projectRoot` | Maven 项目根目录。可以由 `--project` 覆盖。 |
| `cacheRoot` | 项目 workspace、索引和 metadata 的根目录。 |
| `decompileRoot` | 反编译结果根目录。服务不会自动删除其中的文件。 |
| `mavenLocalRepository` | Maven 本地仓库目录。 |
| `additionalJars` | 需要分析但不在 Maven 依赖树中的本地 JAR 列表。 |
| `activeProfiles` | 显式激活的 Maven profile ID 列表。 |
| `allowNetwork` | 当前版本不主动下载依赖；该项为后续 Resolver 接入预留。 |
| `includeTestSources` | 当前实现仍以 main 源码为主；默认不分析测试源码。 |
| `includeGeneratedSources` | 当前默认不分析 generated sources。 |
| `maxCallDepth` | 调用链默认最大深度，默认 `8`。 |
| `maxResults` | 查询默认最大结果数，默认 `100`。 |
| `maxResponseBytes` | 代码文本响应的默认大小限制，默认 `1048576`。 |

## 索引、内存和增量复用

当前索引分成两类：

| 内容 | 内存策略 | 重启时复用策略 |
| --- | --- | --- |
| 项目源码索引 | 完成后保留在内存中，供源码和调用关系查询。 | `source-manifest.json` 输入指纹不变时恢复。 |
| JAR 字节码索引 | 按 JAR 写入 JSON 快照；查询时临时加载对应快照，不长期保留全部 JAR 调用边。 | `manifest.json` 中的 JAR 指纹和快照有效时跳过 ASM 重建。 |

重要边界：

- 首次构建或 JAR 发生变化时，仍可能产生较高的临时内存峰值。
- JAR 是按 artifact 增量复用的：新增、变化、缺失或损坏的 JAR 才需要重新处理；从 Maven 模型删除的 JAR 不再进入当前 manifest，旧快照文件不会自动清理。
- 源码当前是“整份快照复用”，不是逐个 Java 文件的细粒度增量索引；任一源码、POM、classpath 或相关输入变化时，会重建源码索引。
- 启动时仍会读取 JAR 并计算指纹来判断是否变化，但不会因此重新建立全部 ASM 对象。
- 查询外部 JAR 时会产生临时内存和磁盘 I/O；查询结束后对象可被回收。JVM 已提交的堆内存不保证立即归还操作系统，因此 RSS 可能不会立刻下降，但长期存活的索引对象不会按查询次数持续累积。
- 同时运行多个 MCP 进程时，每个进程的 JVM 和项目源码索引都会分别占用内存。

### 持久化目录

默认目录结构：

```text
%USERPROFILE%/.jdt-mcp/
  cache/
    <project-id>/
      workspace/
      index/
        source-index.json
        source-manifest.json
        bytecode/
          <artifact-index>.json
          manifest.json
      metadata/
        project.json
        artifacts.json
      logs/
  decompiled/
    <groupId>/<artifactId>/<version>/<jar-sha256>/
      source/
      metadata.json
```

项目 ID 使用规范化绝对路径的 SHA-256 前 16 位生成。不同项目使用不同的项目缓存目录。

索引快照使用原子写入。manifest 损坏、索引格式变化、快照缺失或指纹不匹配时，会放弃对应快照并重新分析。反编译结果按 JAR SHA-256 长期保存，不会因为服务退出、重启或索引更新自动删除。

## 当前能力和限制

已具备：

- MCP stdio JSON-RPC 生命周期：`initialize`、`ping`、`tools/list`、`tools/call`。
- Maven 单模块和多模块项目加载。
- `compile`、`provided`、`runtime` 依赖及本地 Maven 仓库 JAR 解析。
- Maven parent、BOM import、属性继承、profiles、exclusions 和基础版本仲裁。
- 项目 main Java 源码的 JDT AST 类型、方法和调用边索引。
- JAR 类、方法和字节码调用边索引。
- 源码调用到 JAR 方法的 JVM descriptor 对齐。
- CFR 按需反编译和按 JAR SHA-256 持久化。
- 失败或缺失依赖的 warning 和 `DEGRADED` 状态。

当前限制：

- 主要使用本地 Maven 仓库，不执行 Maven 生命周期，也不主动下载依赖。
- 只分析 main 源码，暂不完整支持测试源码和 generated sources。
- 反射、Spring 动态代理、字符串类名加载、JNI 和运行时生成字节码无法被静态调用图完整确定。
- 接口实现推断、继承关系和动态分派并不等于运行时真实目标。
- 部分 JAR 调用边只有 JAR 文件位置和 `0` 行号，因为它们来自字节码而不是源码。
- JAR 缺失、Maven 模型不完整、编译产物与源码不一致时，绑定调用和调用链可能不完整。
- 当前没有 `find_implementations`、`find_subclasses` 等独立工具。
- 当前没有自动修改代码、自动重构、编译、测试或运行应用的能力。

## 代码目录

```text
src/org/eclipse/jdt/mcp/app/
  Main.java                         启动入口
  config/                           配置加载和校验
  core/                             项目生命周期、缓存和 metadata
  maven/                            Maven POM 和 artifact 模型
  index/                            JDT 源码索引、ASM JAR 索引和查询索引
  decompiler/                       CFR 适配和反编译存储
  server/                           MCP stdio JSON-RPC 服务
  json/                             无额外依赖的 JSON 编解码
```


