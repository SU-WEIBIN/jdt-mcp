# JDT MCP

闈㈠悜 AI 鐨?Java/Maven 椤圭洰鍜?JAR 浠ｇ爜瀵艰埅 MCP 鏈嶅姟銆?
鏈湇鍔℃妸 Maven 椤圭洰妯″瀷銆丣DT 婧愮爜缁戝畾鍒嗘瀽銆丣AR 瀛楄妭鐮佺储寮曞拰鎸夐渶鍙嶇紪璇戣兘鍔涳紝閫氳繃 MCP stdio 鎻愪緵缁?AI銆備竴涓?MCP 杩涚▼鍙鐞嗕竴涓?Maven 椤圭洰锛涢」鐩矾寰勫湪杩涚▼鍚姩鏃剁‘瀹氾紝涓嶈兘閫氳繃 MCP 宸ュ叿鍒囨崲銆?
鏈枃鍓嶅崐閮ㄥ垎鏄粰 AI/MCP 瀹㈡埛绔殑璋冪敤鎸囧崡锛屽悗鍗婇儴鍒嗘槸杩愯銆佺紦瀛樺拰寮€鍙戣鏄庛€侫I 瀹㈡埛绔簲浼樺厛闃呰鈥淎I 璋冪敤濂戠害鈥濃€滃伐鍏烽€夋嫨鈥濆拰鈥滅ǔ瀹氳皟鐢ㄨ鍒欌€濄€?
## AI 璋冪敤濂戠害

### 鏈嶅姟杈圭晫

- 杩欐槸鍙鐨?Java 浠ｇ爜瀵艰埅鏈嶅姟锛屼笉淇敼婧愮爜锛屼笉鑷姩閲嶆瀯锛屼笉鎵ц娴嬭瘯锛屼笉鎵ц Maven 鏋勫缓銆?- 涓€涓繘绋嬪搴斾竴涓?Maven 椤圭洰锛屽繀椤诲湪鍚姩鍛戒护涓彁渚涢」鐩牴鐩綍銆?- 鏈嶅姟涓昏鍒嗘瀽椤圭洰鐨?main Java 婧愮爜銆丮aven 渚濊禆 JAR 鍜岄厤缃腑鐨勯澶栨湰鍦?JAR銆?- 鏈嶅姟鍋氱殑鏄潤鎬佸垎鏋愶紝涓嶇瓑浠蜂簬杩愯鏃惰皟鐢ㄨ窡韪€?- MCP 浣跨敤 stdio JSON-RPC銆俿tdout 鍙兘鍑虹幇鍗忚鍝嶅簲锛涘惎鍔ㄦ棩蹇楀拰閿欒鏃ュ織鍐欏叆 stderr銆?
### 鍒濆鍖栭『搴?
MCP 瀹㈡埛绔缓璁寜涓嬮潰椤哄簭宸ヤ綔锛?
1. 鍙戦€?`initialize`锛屽崗璁増鏈娇鐢?`2024-11-05`銆?2. 鍙戦€?`notifications/initialized` 閫氱煡銆?3. 鍙€夛細璋冪敤 `tools/list` 鑾峰彇褰撳墠宸ュ叿鍒楄〃銆?4. 璋冪敤 `index_status`銆?5. 褰撶姸鎬佷负 `READY` 鎴?`DEGRADED` 鍚庡啀寮€濮嬩緷璧栧畬鏁寸储寮曠殑鏌ヨ銆?6. 鍏堢敤 `search_symbols` 鎵惧埌鍑嗙‘鐨勭被鎴栨柟娉曪紝鍐嶆妸杩斿洖鐨?`id` 浼犵粰鍚庣画宸ュ叿銆?
鍒濆鍖栬姹傜ず渚嬶細

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ai-client","version":"1.0"}}}
```

鐘舵€佹煡璇㈢ず渚嬶細

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"index_status","arguments":{}}}
```

`tools/call` 鐨勪笟鍔″弬鏁板繀椤绘斁鍦?`params.arguments` 瀵硅薄涓€傛病鏈夊弬鏁版椂浼犵┖瀵硅薄 `{}`銆?
### 瑙ｆ瀽宸ュ叿杩斿洖鍊?
鏈疄鐜版妸宸ュ叿缁撴灉缂栫爜鎴?MCP text content锛屽洜姝ゅ鎴风闇€瑕佸啀瑙ｆ瀽涓€娆?JSON锛?
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

姝ｇ‘澶勭悊鏂瑰紡鏄細

1. 璇诲彇 `result.content[0].text`銆?2. 灏嗚繖涓瓧绗︿覆鍐嶆瑙ｆ瀽涓?JSON 瀵硅薄銆?3. 鏍规嵁涓氬姟瀵硅薄涓殑 `found`銆乣state`銆乣warnings`銆乣count` 鍜?`results` 瀛楁缁х画澶勭悊銆?
JSON-RPC 灞傞敊璇細鍑虹幇鍦?`error.code` 鍜?`error.message` 涓€傚伐鍏蜂笟鍔＄粨鏋滀腑鐨?`isError` 褰撳墠姝ｅ父杩斿洖 `false`銆?
### 绱㈠紩鐘舵€佸喅绛?
| `state` | AI 搴斿浣曞鐞?|
| --- | --- |
| `INDEXING` | 绛夊緟鍚庨噸澶嶈皟鐢?`index_status`锛涙鏃舵煡璇㈠彲鑳戒笉瀹屾暣銆?|
| `READY` | 鍙互杩涜瀹屾暣鏌ヨ銆?|
| `DEGRADED` | 鍙互鏌ヨ锛屼絾蹇呴』闃呰 `warnings`銆乣mavenDiagnosticSummary`锛屽苟鍦ㄥ洖绛斾腑璇存槑鍙兘缂哄け銆?|

`index_status` 鐨勯噸瑕佸瓧娈碉細

| 瀛楁 | 鍚箟 |
| --- | --- |
| `indexedArtifacts` | 宸叉湁鍙敤瀛楄妭鐮佸揩鐓х殑 JAR 鏁伴噺銆?|
| `declaredArtifacts` | 褰撳墠 Maven 妯″瀷瑙ｆ瀽鍑虹殑 artifact 鏁伴噺銆?|
| `projectClasses` / `projectMethods` | 椤圭洰婧愮爜绱㈠紩涓殑绫诲瀷鍜屾柟娉曟暟閲忋€?|
| `bytecodeClasses` / `bytecodeMethods` | 宸茬储寮?JAR 瀛楄妭鐮佷腑鐨勭被鍨嬪拰鏂规硶鏁伴噺銆?|
| `indexedClasses` / `indexedMethods` | 椤圭洰婧愮爜涓?JAR 瀛楄妭鐮佺殑鍚堣鏁伴噺銆?|
| `callEdges` | 椤圭洰婧愮爜鍜?JAR 瀛楄妭鐮佽皟鐢ㄨ竟鐨勫悎璁℃暟閲忋€?|
| `warnings` | 缂哄け JAR銆佽В鏋愬け璐ャ€佺紦瀛樻仮澶嶄俊鎭拰鍏朵粬璇婃柇銆?|
| `mavenResolutionState` | Maven 妯″瀷瑙ｆ瀽鐘舵€併€?|
| `mavenDiagnosticSummary` | Maven 渚濊禆瑙ｆ瀽璇婃柇鎽樿銆?|

浠?`[INFO/source-cache]` 鎴?`[INFO/bytecode-cache]` 寮€澶寸殑 warning 閫氬父鏄紦瀛樺鐢ㄤ俊鎭紝涓嶄唬琛ㄧ储寮曞け璐ャ€傚叾浠?warning 闇€瑕佺粨鍚堢粨鏋滆皑鎱庤В閲娿€?
## 宸ュ叿閫夋嫨

| 鐢ㄦ埛闂 | 棣栭€夊伐鍏?| 涓嬩竴姝?|
| --- | --- | --- |
| 鎵剧被鎴栨柟娉?| `search_symbols` | 鍙栫粨鏋滀腑鐨?`id`锛屽啀璋冪敤 `get_class` 鎴?`get_method`銆?|
| 鎼滅储婧愮爜鏂囨湰 | `search_text` | 鏍规嵁 `file` 鍜?`line` 闃呰婧愮爜浣嶇疆銆?|
| 鏌ョ湅绫讳唬鐮?| `get_class` | 椤圭洰绫昏繑鍥炴簮鐮侊紱JAR 绫诲彲鑳借Е鍙?CFR 鍙嶇紪璇戙€?|
| 鏌ョ湅鏂规硶浠ｇ爜 | `get_method` | 椤圭洰鏂规硶杩斿洖婧愮爜鐗囨锛汮AR 鏂规硶杩斿洖鎵€灞炲弽缂栬瘧绫汇€?|
| 鏌ョ湅鏌愪釜 JAR 鐨勭被 | `inspect_jar` | 浼樺厛浼犵簿纭?Maven `coordinate`锛屽啀鐢?`query` 杩囨护绫诲悕銆?|
| 椤圭洰鍝噷璋冪敤浜嗘煇涓?API | `find_project_usages` | 浼犳悳绱㈢粨鏋滀腑鐨勬柟娉?`symbolId`锛屽苟鎸?`caller.source` 杩囨护椤圭洰璋冪敤鑰呫€?|
| 璋佽皟鐢ㄤ簡杩欎釜鏂规硶 | `find_callers` | 浼犳柟娉?`symbolId`銆傜粨鏋滃彲鑳芥潵鑷」鐩拰 JAR銆?|
| 涓€涓柟娉曠洿鎺ヨ皟鐢ㄤ簡浠€涔?| `find_callees` | 浼犳柟娉?`symbolId`銆?|
| 缁х画杩借釜璋冪敤閾?| `trace_call_chain` | 浼犺捣鐐规柟娉?`symbolId`锛岃缃?`maxDepth` 鍜?`maxResults`銆?|
| 鏌ョ湅椤圭洰鍜岀紦瀛樹綅缃?| `project_info` | 涓嶉渶瑕佸弬鏁般€?|
| 鏌ョ湅绱㈠紩鏄惁鍙敤 | `index_status` | 涓嶉渶瑕佸弬鏁般€?|

## 绋冲畾璋冪敤瑙勫垯

### 鍏堟悳绱紝鍐嶅鐢?ID

涓嶈璁?AI 鐚?`symbolId` 鎴?`id`銆傛帹鑽愭祦绋嬫槸锛?
```text
search_symbols -> 閫夋嫨绮剧‘缁撴灉 -> 澶嶇敤缁撴灉涓殑 id
                         |
                         +-> get_class / get_method
                         +-> find_project_usages / find_callers
                         +-> find_callees
                         +-> trace_call_chain
```

鍏崇郴鏌ヨ浼樺厛浣跨敤绋冲畾 ID锛屽彧鏈夋病鏈?ID 鏃舵墠浣跨敤 `query`銆備娇鐢?`query` 鏃讹紝鏈嶅姟浼氬彇鎼滅储缁撴灉涓殑绗竴涓尮閰嶉」锛涢噸杞芥柟娉曟垨鍚屽悕绫诲彲鑳藉洜姝ら€夐敊銆?
鏂规硶 ID 浣跨敤 JVM descriptor 鍖哄垎閲嶈浇銆備緥濡傦細

```text
method:org/eclipse/jdt/core/dom/ASTParser#createAST(Lorg/eclipse/core/runtime/IProgressMonitor;)Lorg/eclipse/jdt/core/dom/ASTNode;
```

甯歌绗﹀彿瀛楁锛?
| 瀛楁 | 鍚箟 |
| --- | --- |
| `id` | 鍚庣画鏌ヨ浣跨敤鐨勭ǔ瀹氱鍙?ID銆?|
| `kind` | `TYPE` 鎴?`METHOD`銆?|
| `name` | 绠€鍗曠被鍚嶆垨鏂规硶鍚嶃€?|
| `qualifiedName` | 鍏ㄩ檺瀹氬悕鎴栧甫鏂规硶鍙傛暟/杩斿洖鍊肩殑绛惧悕銆?|
| `signature` | 鐢ㄤ簬鎼滅储鍜屽睍绀虹殑绛惧悕銆?|
| `source` | 閫氬父涓?`project`銆乣bytecode` 鎴?JDT 缁戝畾浜х敓鐨?`dependency`銆?|
| `module` | 椤圭洰妯″潡淇℃伅鎴?Maven artifact 鍧愭爣銆?|
| `file` | 婧愮爜鏂囦欢鎴?JAR 鏂囦欢璺緞銆?|
| `startLine` / `endLine` | 婧愮爜绗﹀彿鐨勮鑼冨洿锛涘瓧鑺傜爜绗﹀彿閫氬父涓?`0`銆?|
| `declaringTypeId` | 鎵€灞炵被鍨?ID锛堝鏋滃彲鐢級銆?|

`source: dependency` 鍙兘鍙槸婧愮爜缁戝畾浜х敓鐨勪緷璧栧崰浣嶇锛屼笉涓€瀹氬寘鍚彲鐩存帴鍙嶇紪璇戠殑 JAR 鏂囦欢淇℃伅銆傞渶瑕佹煡鐪嬬涓夋柟 JAR 浠ｇ爜鏃讹紝浼樺厛浠?`source: bytecode` 鐨勬悳绱㈢粨鏋滃彇寰?ID銆?
### 鎺у埗缁撴灉瑙勬ā

- `maxResults` 榛樿鏉ヨ嚜閰嶇疆锛岄粯璁ゅ€间负 `100`锛涗紶鍏ュ€艰嚦灏戞寜 `1` 澶勭悊銆?- `maxDepth` 榛樿鏉ヨ嚜閰嶇疆锛岄粯璁ゅ€间负 `8`锛涘彧瀵?`trace_call_chain` 鐢熸晥銆?- 鏌ヨ缁撴灉鍙兘鍥犱负鏁伴噺涓婇檺鑰屼笉瀹屾暣銆傝皟鐢ㄩ摼杩樹細杩涜寰幆妫€娴嬨€?- `search_symbols` 鏄ぇ灏忓啓涓嶆晱鎰熺殑瀛愪覆鍖归厤锛屼笉鏄鍒欒〃杈惧紡銆?- `search_text` 榛樿澶у皬鍐欎笉鏁忔劅锛沗path` 鏄枃浠惰矾寰勫瓙涓茶繃婊ゅ櫒锛屼笉鏄?glob銆?- 涓嶈涓€娆¤姹傝繃澶х殑璋冪敤閾撅紱鍏堢敤杈冨皬鐨?`maxDepth` 鍜?`maxResults`锛屽啀閫愭鎵╁ぇ銆?
## 宸ュ叿鍙傛暟鍜岃繑鍥炲€?
### `project_info`

鐢ㄩ€旓細鏌ョ湅褰撳墠杩涚▼缁戝畾鐨勯」鐩€丮aven 妯″瀷鍜岀紦瀛樿矾寰勩€?
鍙傛暟锛?
```json
{}
```

杩斿洖瀵硅薄鍖呭惈 `projectId`銆乣projectRoot`銆乣projectCacheRoot`銆乣workspaceRoot`銆乣indexRoot`銆乣decompileRoot`銆丮aven 淇℃伅銆侀澶?JAR銆佸綋鍓?`state` 鍜?`indexWarnings`銆?
### `index_status`

鐢ㄩ€旓細鏌ョ湅绱㈠紩鏄惁瀹屾垚锛屼互鍙婇」鐩?JAR 鐨勭粺璁′俊鎭€?
鍙傛暟锛?
```json
{}
```

绱㈠紩鏈畬鎴愭椂涓嶈鎶婄┖缁撴灉褰撴垚鈥滈」鐩腑涓嶅瓨鍦ㄨ绫绘垨鏂规硶鈥濄€?
### `search_symbols`

鐢ㄩ€旓細鎸夌被鍚嶃€佹柟娉曞悕銆佸叏闄愬畾鍚嶆垨绛惧悕鎼滅储椤圭洰婧愮爜鍜屽凡鎸佷箙鍖栫殑 JAR 瀛楄妭鐮佺储寮曘€?
鍙傛暟锛?
| 鍙傛暟 | 绫诲瀷 | 璇存槑 |
| --- | --- | --- |
| `query` | string | 寤鸿濉啓绫诲悕銆佹柟娉曞悕鎴栫鍚嶇墖娈碉紱绌哄瓧绗︿覆浼氬尮閰嶄换鎰忕鍙枫€?|
| `kind` | string | 鍙€夛紝`TYPE` 鎴?`METHOD`銆?|
| `maxResults` | integer | 鍙€夛紝榛樿 `100`銆?|

绀轰緥锛?
```json
{"query":"ASTParser","kind":"TYPE","maxResults":10}
```

杩斿洖锛?
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

椤圭洰婧愮爜鎼滅储缁撴灉鍜?JAR 瀛楄妭鐮佹悳绱㈢粨鏋滄贩鍚堣繑鍥烇紱椤圭洰婧愮爜缁撴灉閫氬父鎺掑湪鍓嶉潰銆傝繑鍥炴暟閲忕敱 `maxResults` 闄愬埗銆?
### `search_text`

鐢ㄩ€旓細鍦ㄩ」鐩?main Java 婧愮爜涓寜鏂囨湰鏌ユ壘璋冪敤銆侀厤缃垨瀹炵幇浣嶇疆銆?
鍙傛暟锛?
| 鍙傛暟 | 绫诲瀷 | 璇存槑 |
| --- | --- | --- |
| `query` | string | 蹇呭～锛屼笉鑳戒负绌恒€?|
| `path` | string | 鍙€夛紝鏂囦欢璺緞瀛愪覆锛屼緥濡?`src/main/java`銆?|
| `caseSensitive` | boolean | 鍙€夛紝榛樿 `false`銆?|
| `maxResults` | integer | 鍙€夛紝榛樿 `100`銆?|

绀轰緥锛?
```json
{"query":"createAST","path":"src/main/java","caseSensitive":false,"maxResults":20}
```

杩斿洖瀵硅薄褰㈠锛?
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

褰撳墠鍙壂鎻?main Java 婧愮爜锛屼笉鎵弿娴嬭瘯婧愮爜鍜?generated sources銆?
### `get_class`

鐢ㄩ€旓細鍙栧緱涓€涓被鍨嬬殑绱㈠紩淇℃伅鍜屼唬鐮併€?
鍙傛暟锛?
| 鍙傛暟 | 绫诲瀷 | 璇存槑 |
| --- | --- | --- |
| `id` | string | 鎺ㄨ崘锛岀洿鎺ヤ娇鐢?`search_symbols` 杩斿洖鐨?`TYPE` 缁撴灉 ID銆?|
| `query` | string | 娌℃湁 `id` 鏃朵娇鐢紝鏈嶅姟鍙栫涓€涓?`TYPE` 鍖归厤椤广€?|

绀轰緥锛?
```json
{"id":"type:org/eclipse/jdt/core/dom/ASTParser"}
```

鎵惧埌鏃惰繑鍥?`found:true`銆佺鍙峰瓧娈典互鍙?`sourceText`銆傞」鐩簮鐮佺被鐨?`sourceText` 鏉ヨ嚜鍘熸枃浠讹紱JAR 绫荤涓€娆℃煡鐪嬫椂鍙兘瑙﹀彂 CFR 鍙嶇紪璇戯紝骞堕檮甯︼細

- `decompiledFile`锛氭寔涔呭寲鐨勫弽缂栬瘧 Java 鏂囦欢銆?- `decompiler`锛氫娇鐢ㄧ殑鍙嶇紪璇戝櫒鍚嶇О銆?- `sourceText`锛氬彈 `maxResponseBytes` 闄愬埗鐨勫弽缂栬瘧鏂囨湰銆?- `decompileError`锛氬弽缂栬瘧澶辫触鏃剁殑閿欒淇℃伅銆?
鎵句笉鍒版椂閫氬父杩斿洖锛?
```json
{"found":false,"results":[]}
```

### `get_method`

鐢ㄩ€旓細鍙栧緱涓€涓柟娉曠殑绱㈠紩淇℃伅鍜屼唬鐮併€?
鍙傛暟锛?
| 鍙傛暟 | 绫诲瀷 | 璇存槑 |
| --- | --- | --- |
| `id` | string | 鎺ㄨ崘锛岀洿鎺ヤ娇鐢?`search_symbols` 杩斿洖鐨?`METHOD` 缁撴灉 ID銆?|
| `query` | string | 娌℃湁 `id` 鏃朵娇鐢紝鏈嶅姟鍙栫涓€涓?`METHOD` 鍖归厤椤广€?|

绀轰緥锛?
```json
{"id":"method:org/eclipse/jdt/core/dom/ASTParser#createAST(Lorg/eclipse/core/runtime/IProgressMonitor;)Lorg/eclipse/jdt/core/dom/ASTNode;"}
```

椤圭洰鏂规硶杩斿洖婧愮爜鐗囨鍜屼綅缃€侸AR 鏂规硶杩斿洖鎵€灞炲弽缂栬瘧绫荤殑浠ｇ爜锛屼笉鑳戒繚璇佸彧鎴彇璇ユ柟娉曟湰韬€?
### `inspect_jar`

鐢ㄩ€旓細鏌ョ湅涓€涓凡瑙ｆ瀽 Maven artifact 鐨勭被鍒楄〃鍜岀储寮曠粺璁°€?
寤鸿濮嬬粓浼犵簿纭?`coordinate`锛?
```json
{
  "coordinate":"org.eclipse.jdt:jdt-core:3.38.0",
  "query":"ASTParser",
  "maxResults":20
}
```

鍙傛暟瑙勫垯锛?
- `coordinate` 鏄簿纭殑 Maven 鍧愭爣锛岄€氬父涓?`groupId:artifactId:version`銆?- `query` 鍦ㄦ彁渚?`coordinate` 鏃剁敤浜庤繃婊ょ被鍚嶃€?- 濡傛灉涓嶆彁渚?`coordinate`锛宍query` 浼氬厛浣滀负鍧愭爣鐗囨瀵绘壘绗竴涓?artifact锛涜繖绉嶆ā寮忎笉閫傚悎鍚屾椂鍋氱被鍚嶈繃婊わ紝AI 搴斿敖閲忛伩鍏嶃€?- 杩斿洖 `found:false` 琛ㄧず鍧愭爣涓嶅瓨鍦ㄦ垨鏈褰撳墠 Maven 妯″瀷鍔犺浇銆?
杩斿洖瀵硅薄褰㈠锛?
```json
{
  "found":true,
  "artifact":{"coordinate":"org.eclipse.jdt:jdt-core:3.38.0", "...":"..."},
  "classCount":1234,
  "classes":[/* TYPE 绗﹀彿 */],
  "truncated":false
}
```

### `find_project_usages`

鐢ㄩ€旓細鏌ユ壘鏌愪釜鏂规硶鐨勮皟鐢ㄨ竟锛岄€氬父鐢ㄤ簬鍥炵瓟鈥滀簩寮€椤圭洰鍝噷璋冪敤浜嗚繖涓?JAR API鈥濄€?
鍙傛暟锛?
```json
{"symbolId":"method:com/vendor/Client#execute(Ljava/lang/String;)V","maxResults":50}
```

褰撳墠瀹炵幇浼氭煡璇㈤」鐩簮鐮佸拰鎸佷箙鍖?JAR 绱㈠紩涓殑璋冪敤杈广€傝嫢鍙渶瑕侀」鐩簮鐮佽皟鐢ㄨ€咃紝璇峰湪缁撴灉鐨?`caller.source` 涓繚鐣?`project`銆?
### `find_callers`

鐢ㄩ€旓細鏌ユ壘鏌愪釜鏂规硶鐨勮皟鐢ㄨ€咃紝缁撴灉鍙兘鏉ヨ嚜椤圭洰婧愮爜鎴?JAR 瀛楄妭鐮併€?
鍙傛暟锛?
```json
{"symbolId":"method:com/vendor/Client#execute(Ljava/lang/String;)V","maxResults":50}
```

`find_project_usages` 鍜?`find_callers` 褰撳墠鍏变韩鍚屼竴鏌ヨ瀹炵幇锛涘尯鍒富瑕佹槸璇箟鍛藉悕銆傞渶瑕佷弗鏍煎尯鍒嗘潵婧愭椂锛屼娇鐢ㄨ繑鍥炵殑 `caller.source`銆乣caller.module` 鍜?`caller.file` 杩囨护銆?
涓よ€呰繑鍥炲璞″舰濡傦細

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

`resolution` 甯歌鍊硷細

- `binding`锛欽DT 婧愮爜缁戝畾瑙ｆ瀽銆?- `direct-bytecode-call`锛欽AR 瀛楄妭鐮佺洿鎺ヨ皟鐢ㄣ€?- `interface-dispatch`锛氭帴鍙ｈ皟鐢ㄦ寚浠ゆ垨鎺ュ彛鍒嗘淳銆?- `invokedynamic`锛氬姩鎬佽皟鐢ㄦ寚浠ゃ€?- `unresolved`锛氶潤鎬佸垎鏋愭棤娉曠‘瀹氱洰鏍囥€?
### `find_callees`

鐢ㄩ€旓細鏌ユ壘涓€涓」鐩柟娉曟垨 JAR 鏂规硶鐨勭洿鎺ヨ璋冪敤鏂规硶銆?
鍙傛暟锛?
```json
{"symbolId":"method:example/App#run()V","maxResults":50}
```

杩斿洖瀵硅薄鍖呭惈 `found`銆乣caller` 鍜?`results`銆俙results` 涓槸鐩存帴璋冪敤杈癸紝涓嶄細鑷姩灞曞紑涓嬩竴灞傘€?
### `trace_call_chain`

鐢ㄩ€旓細浠庝竴涓柟娉曞紑濮嬶紝鎸夊箍搴︿紭鍏堟柟鍚戣拷韪叾鍚戜笅璋冪敤閾俱€?
鍙傛暟锛?
| 鍙傛暟 | 绫诲瀷 | 璇存槑 |
| --- | --- | --- |
| `symbolId` | string | 鎺ㄨ崘锛岃捣鐐规柟娉?ID銆?|
| `query` | string | 娌℃湁 ID 鏃朵娇鐢紝鍙栫涓€涓?`METHOD` 鍖归厤椤广€?|
| `maxDepth` | integer | 鍙€夛紝榛樿 `8`銆?|
| `maxResults` | integer | 鍙€夛紝榛樿 `100`銆?|

绀轰緥锛?
```json
{
  "symbolId":"method:example/App#run()V",
  "maxDepth":4,
  "maxResults":40
}
```

杩斿洖瀵硅薄鍖呭惈 `found`銆乣start` 鍜?`results`銆傛瘡涓粨鏋滃寘鍚細

- `depth`锛氫粠璧风偣寮€濮嬬殑杈规繁搴︺€?- `path`锛氫粠璧风偣鍒板綋鍓嶇洰鏍囩殑绗﹀彿 ID 鍒楄〃銆?- `call`锛氳皟鐢ㄨ竟淇℃伅銆?
## 鎺ㄨ崘鐨?AI 宸ヤ綔娴?
### 鍦烘櫙涓€锛氬垎鏋愪簩寮€浠ｇ爜璋冪敤浜嗗摢涓涓夋柟 API

```text
1. index_status
2. search_symbols锛屾悳绱㈤」鐩柟娉曟垨鐩爣 API锛宬ind 鍙涓?METHOD
3. 澶嶇敤缁撴灉涓殑 id
4. find_project_usages锛屼紶 symbolId
5. 瀵硅繑鍥炵殑 caller.source == "project" 鐨勮皟鐢ㄧ偣鍋氳В閲?6. get_method 鎴?search_text 鏌ョ湅璋冪敤涓婁笅鏂?```

### 鍦烘櫙浜岋細闃呰涓€涓涓夋柟 JAR 鐨勫疄鐜?
```text
1. index_status
2. inspect_jar锛屼紶绮剧‘ coordinate
3. 鐢ㄨ繑鍥炵殑绫?id 璋冪敤 get_class
4. 鐢?search_symbols 鎼滅储鐩爣鏂规硶
5. 鐢ㄦ柟娉?id 璋冪敤 get_method
6. 鐢?find_callees 鎴?trace_call_chain 缁х画鍒嗘瀽鍐呴儴璋冪敤
```

### 鍦烘櫙涓夛細鍥炵瓟鈥滆皝璋冪敤浜嗚繖涓柟娉曗€?
```text
1. search_symbols锛屽彇寰楀敮涓€鎴栨渶绮剧‘鐨?METHOD id
2. find_callers锛屼紶 symbolId
3. 鏍规嵁 caller.source銆乧aller.module 鍜?caller.file 鍖哄垎椤圭洰璋冪敤鑰呬笌 JAR 璋冪敤鑰?4. 闇€瑕佺户缁悜涓婂垎鏋愭椂锛屽 caller.id 閲嶅璋冪敤 find_callers
```

## 杩愯鍜屾瀯寤?
### 杩愯鐜

- Java 17 鎴栨洿楂樼増鏈€?- Maven 3.6 鎴栨洿楂樼増鏈€?- Maven 鏈湴浠撳簱锛岄粯璁ゆ槸 `~/.m2/repository`銆?- 椤圭洰鏍圭洰褰曞繀椤诲寘鍚?`pom.xml`銆?
鏈嶅姟涓嶅惎鍔?Eclipse IDE锛屼篃涓嶈姹傜敤鎴烽厤缃?Eclipse workspace銆侸DT Core 閫氳繃 headless `ASTParser` 浣跨敤銆?
### 鏋勫缓

鍦?`org.eclipse.jdt.mcp.app` 鐩綍鎵ц锛?
```powershell
# 鍙€夛細鏄惧紡鎸囧畾鐢ㄤ簬鏋勫缓鍜岃繍琛岀殑 JDK
$env:JDT_MCP_JAVA_HOME = 'C:\path\to\jdk-21'
.\build.ps1
```

鏋勫缓鑴氭湰浼氳鍙?POM 涓殑 `maven.compiler.release`锛屾鏌?`JDT_MCP_JAVA_HOME`銆乣JAVA_HOME`銆丳ATH 鍜屽父瑙?JDK 瀹夎鐩綍锛岄€夋嫨鍏煎鐗堟湰锛岃缃?Maven 鐨?`JAVA_HOME`锛屽苟鏍稿 `mvn -version` 鐨?Java home銆傛壘涓嶅埌鍏煎 JDK 鏃朵細鐩存帴鎶ュ憡瑕佹眰鐨勭増鏈拰鍙鐩栫殑鐜鍙橀噺銆?
### 鍚姩

```powershell
# 鍙渷鐣?JDT_MCP_JAVA_HOME锛岃鑴氭湰鑷姩閫夋嫨鍏煎 JDK
.\build.ps1 -Run --project 'C:\work\my-maven-project'
```

浣跨敤閰嶇疆鏂囦欢锛?
```powershell
.\build.ps1 -Run `
    --project 'C:\work\my-maven-project' `
    --config 'C:\work\jdt-mcp.json'
```

鐩存帴鍚姩宸叉瀯寤轰骇鐗╋細

```powershell
$javaHome = 'C:\path\to\jdk-21'
$cp = (Join-Path (Get-Location) 'target/classes') + [IO.Path]::PathSeparator + (Get-Content -Raw target/classpath.txt).Trim()
& (Join-Path $javaHome 'bin/java.exe') `
    -cp $cp `
    org.eclipse.jdt.mcp.app.Main `
    --project 'C:\work\my-maven-project'
```

### MCP 瀹㈡埛绔厤缃師鍒?
涓嶅悓 AI 瀹㈡埛绔殑閰嶇疆瀛楁鍚嶇О涓嶅悓锛屼絾閮藉簲婊¤冻锛?
- 浼犺緭鏂瑰紡涓?stdio銆?- 鍚姩鍛戒护鏈€缁堟墽琛?`org.eclipse.jdt.mcp.app.Main` 鎴?`build.ps1 -Run`銆?- 灏嗛」鐩矾寰勬斁鍦ㄥ惎鍔ㄥ弬鏁?`--project` 涓€?- 灏嗛厤缃枃浠舵斁鍦ㄥ惎鍔ㄥ弬鏁?`--config` 涓紙濡傛灉浣跨敤锛夈€?- 涓嶈鎶婃棩蹇楅噸瀹氬悜鍒?stdout銆?
## 閰嶇疆鏂囦欢

瀹屾暣绀轰緥瑙侊細[jdt-mcp.example.json](./jdt-mcp.example.json)銆?
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

閰嶇疆鏂囦欢涓殑鐩稿璺緞鐩稿浜庨厤缃枃浠舵墍鍦ㄧ洰褰曡В鏋愩€傚懡浠よ `--project` 浼樺厛浜庨厤缃枃浠朵腑鐨?`projectRoot`銆?
| 閰嶇疆椤?| 璇存槑 |
| --- | --- |
| `projectRoot` | Maven 椤圭洰鏍圭洰褰曘€傚彲浠ョ敱 `--project` 瑕嗙洊銆?|
| `cacheRoot` | 椤圭洰 workspace銆佺储寮曞拰 metadata 鐨勬牴鐩綍銆?|
| `decompileRoot` | 鍙嶇紪璇戠粨鏋滄牴鐩綍銆傛湇鍔′笉浼氳嚜鍔ㄥ垹闄ゅ叾涓殑鏂囦欢銆?|
| `mavenLocalRepository` | Maven 鏈湴浠撳簱鐩綍銆?|
| `additionalJars` | 闇€瑕佸垎鏋愪絾涓嶅湪 Maven 渚濊禆鏍戜腑鐨勬湰鍦?JAR 鍒楄〃銆?|
| `activeProfiles` | 鏄惧紡婵€娲荤殑 Maven profile ID 鍒楄〃銆?|
| `allowNetwork` | 褰撳墠鐗堟湰涓嶄富鍔ㄤ笅杞戒緷璧栵紱璇ラ」涓哄悗缁?Resolver 鎺ュ叆棰勭暀銆?|
| `includeTestSources` | 褰撳墠瀹炵幇浠嶄互 main 婧愮爜涓轰富锛涢粯璁や笉鍒嗘瀽娴嬭瘯婧愮爜銆?|
| `includeGeneratedSources` | 褰撳墠榛樿涓嶅垎鏋?generated sources銆?|
| `maxCallDepth` | 璋冪敤閾鹃粯璁ゆ渶澶ф繁搴︼紝榛樿 `8`銆?|
| `maxResults` | 鏌ヨ榛樿鏈€澶х粨鏋滄暟锛岄粯璁?`100`銆?|
| `maxResponseBytes` | 浠ｇ爜鏂囨湰鍝嶅簲鐨勯粯璁ゅぇ灏忛檺鍒讹紝榛樿 `1048576`銆?|

## 绱㈠紩銆佸唴瀛樺拰澧為噺澶嶇敤

褰撳墠绱㈠紩鍒嗘垚涓ょ被锛?
| 鍐呭 | 鍐呭瓨绛栫暐 | 閲嶅惎鏃跺鐢ㄧ瓥鐣?|
| --- | --- | --- |
| 椤圭洰婧愮爜绱㈠紩 | 瀹屾垚鍚庝繚鐣欏湪鍐呭瓨涓紝渚涙簮鐮佸拰璋冪敤鍏崇郴鏌ヨ銆?| `source-manifest.json` 杈撳叆鎸囩汗涓嶅彉鏃舵仮澶嶃€?|
| JAR 瀛楄妭鐮佺储寮?| 鎸?JAR 鍐欏叆 JSON 蹇収锛涙煡璇㈡椂涓存椂鍔犺浇瀵瑰簲蹇収锛屼笉闀挎湡淇濈暀鍏ㄩ儴 JAR 璋冪敤杈广€?| `manifest.json` 涓殑 JAR 鎸囩汗鍜屽揩鐓ф湁鏁堟椂璺宠繃 ASM 閲嶅缓銆?|

閲嶈杈圭晫锛?
- 棣栨鏋勫缓鎴?JAR 鍙戠敓鍙樺寲鏃讹紝浠嶅彲鑳戒骇鐢熻緝楂樼殑涓存椂鍐呭瓨宄板€笺€?- JAR 鏄寜 artifact 澧為噺澶嶇敤鐨勶細鏂板銆佸彉鍖栥€佺己澶辨垨鎹熷潖鐨?JAR 鎵嶉渶瑕侀噸鏂板鐞嗭紱浠?Maven 妯″瀷鍒犻櫎鐨?JAR 涓嶅啀杩涘叆褰撳墠 manifest锛屾棫蹇収鏂囦欢涓嶄細鑷姩娓呯悊銆?- 婧愮爜褰撳墠鏄€滄暣浠藉揩鐓у鐢ㄢ€濓紝涓嶆槸閫愪釜 Java 鏂囦欢鐨勭粏绮掑害澧為噺绱㈠紩锛涗换涓€婧愮爜銆丳OM銆乧lasspath 鎴栫浉鍏宠緭鍏ュ彉鍖栨椂锛屼細閲嶅缓婧愮爜绱㈠紩銆?- 鍚姩鏃朵粛浼氳鍙?JAR 骞惰绠楁寚绾规潵鍒ゆ柇鏄惁鍙樺寲锛屼絾涓嶄細鍥犳閲嶆柊寤虹珛鍏ㄩ儴 ASM 瀵硅薄銆?- 鏌ヨ澶栭儴 JAR 鏃朵細浜х敓涓存椂鍐呭瓨鍜岀鐩?I/O锛涙煡璇㈢粨鏉熷悗瀵硅薄鍙鍥炴敹銆侸VM 宸叉彁浜ょ殑鍫嗗唴瀛樹笉淇濊瘉绔嬪嵆褰掕繕鎿嶄綔绯荤粺锛屽洜姝?RSS 鍙兘涓嶄細绔嬪埢涓嬮檷锛屼絾闀挎湡瀛樻椿鐨勭储寮曞璞′笉浼氭寜鏌ヨ娆℃暟鎸佺画绱Н銆?- 鍚屾椂杩愯澶氫釜 MCP 杩涚▼鏃讹紝姣忎釜杩涚▼鐨?JVM 鍜岄」鐩簮鐮佺储寮曢兘浼氬垎鍒崰鐢ㄥ唴瀛樸€?
### 鎸佷箙鍖栫洰褰?
榛樿鐩綍缁撴瀯锛?
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

椤圭洰 ID 浣跨敤瑙勮寖鍖栫粷瀵硅矾寰勭殑 SHA-256 鍓?16 浣嶇敓鎴愩€備笉鍚岄」鐩娇鐢ㄤ笉鍚岀殑椤圭洰缂撳瓨鐩綍銆?
绱㈠紩蹇収浣跨敤鍘熷瓙鍐欏叆銆俶anifest 鎹熷潖銆佺储寮曟牸寮忓彉鍖栥€佸揩鐓х己澶辨垨鎸囩汗涓嶅尮閰嶆椂锛屼細鏀惧純瀵瑰簲蹇収骞堕噸鏂板垎鏋愩€傚弽缂栬瘧缁撴灉鎸?JAR SHA-256 闀挎湡淇濆瓨锛屼笉浼氬洜涓烘湇鍔￠€€鍑恒€侀噸鍚垨绱㈠紩鏇存柊鑷姩鍒犻櫎銆?
## 褰撳墠鑳藉姏鍜岄檺鍒?
宸插叿澶囷細

- MCP stdio JSON-RPC 鐢熷懡鍛ㄦ湡锛歚initialize`銆乣ping`銆乣tools/list`銆乣tools/call`銆?- Maven 鍗曟ā鍧楀拰澶氭ā鍧楅」鐩姞杞姐€?- `compile`銆乣provided`銆乣runtime` 渚濊禆鍙婃湰鍦?Maven 浠撳簱 JAR 瑙ｆ瀽銆?- Maven parent銆丅OM import銆佸睘鎬х户鎵裤€乸rofiles銆乪xclusions 鍜屽熀纭€鐗堟湰浠茶銆?- 椤圭洰 main Java 婧愮爜鐨?JDT AST 绫诲瀷銆佹柟娉曞拰璋冪敤杈圭储寮曘€?- JAR 绫汇€佹柟娉曞拰瀛楄妭鐮佽皟鐢ㄨ竟绱㈠紩銆?- 婧愮爜璋冪敤鍒?JAR 鏂规硶鐨?JVM descriptor 瀵归綈銆?- CFR 鎸夐渶鍙嶇紪璇戝拰鎸?JAR SHA-256 鎸佷箙鍖栥€?- 澶辫触鎴栫己澶变緷璧栫殑 warning 鍜?`DEGRADED` 鐘舵€併€?
褰撳墠闄愬埗锛?
- 涓昏浣跨敤鏈湴 Maven 浠撳簱锛屼笉鎵ц Maven 鐢熷懡鍛ㄦ湡锛屼篃涓嶄富鍔ㄤ笅杞戒緷璧栥€?- 鍙垎鏋?main 婧愮爜锛屾殏涓嶅畬鏁存敮鎸佹祴璇曟簮鐮佸拰 generated sources銆?- 鍙嶅皠銆丼pring 鍔ㄦ€佷唬鐞嗐€佸瓧绗︿覆绫诲悕鍔犺浇銆丣NI 鍜岃繍琛屾椂鐢熸垚瀛楄妭鐮佹棤娉曡闈欐€佽皟鐢ㄥ浘瀹屾暣纭畾銆?- 鎺ュ彛瀹炵幇鎺ㄦ柇銆佺户鎵垮叧绯诲拰鍔ㄦ€佸垎娲惧苟涓嶇瓑浜庤繍琛屾椂鐪熷疄鐩爣銆?- 閮ㄥ垎 JAR 璋冪敤杈瑰彧鏈?JAR 鏂囦欢浣嶇疆鍜?`0` 琛屽彿锛屽洜涓哄畠浠潵鑷瓧鑺傜爜鑰屼笉鏄簮鐮併€?- JAR 缂哄け銆丮aven 妯″瀷涓嶅畬鏁淬€佺紪璇戜骇鐗╀笌婧愮爜涓嶄竴鑷存椂锛岀粦瀹氳皟鐢ㄥ拰璋冪敤閾惧彲鑳戒笉瀹屾暣銆?- 褰撳墠娌℃湁 `find_implementations`銆乣find_subclasses` 绛夌嫭绔嬪伐鍏枫€?- 褰撳墠娌℃湁鑷姩淇敼浠ｇ爜銆佽嚜鍔ㄩ噸鏋勩€佺紪璇戙€佹祴璇曟垨杩愯搴旂敤鐨勮兘鍔涖€?
## 浠ｇ爜鐩綍

```text
src/org/eclipse/jdt/mcp/app/
  Main.java                         鍚姩鍏ュ彛
  config/                           閰嶇疆鍔犺浇鍜屾牎楠?  core/                             椤圭洰鐢熷懡鍛ㄦ湡銆佺紦瀛樺拰 metadata
  maven/                            Maven POM 鍜?artifact 妯″瀷
  index/                            JDT 婧愮爜绱㈠紩銆丄SM JAR 绱㈠紩鍜屾煡璇㈢储寮?  decompiler/                       CFR 閫傞厤鍜屽弽缂栬瘧瀛樺偍
  server/                           MCP stdio JSON-RPC 鏈嶅姟
  json/                             鏃犻澶栦緷璧栫殑 JSON 缂栬В鐮?```

瀹炵幇璁″垝鍜屽綋鍓嶇姸鎬佽锛歔docs/jdt-mcp-implementation-plan.md](../docs/jdt-mcp-implementation-plan.md)銆?