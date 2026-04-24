# 多Agent会议 App

> 多 Agent 自主讨论决策系统。
> Agent 通过 LLM 自行判断是否发言、何时发言，对抗性辩论产出决策建议。
> Preset 驱动的动态委员会 — 10+ 内置场景，支持自定义角色与工具。

---

## 架构总览（v7 — Clean Architecture 多模块）

```
InvestmentCommittee/
├── :core                              ← 纯 Kotlin JVM 模块（零 Android 依赖）
│   └── core/src/main/kotlin/
│       ├── ExperienceStore.kt          ← 经验存储接口
│       └── PresetLoader.kt            ← JSON 预设加载器
│
├── :data                              ← Android Library 模块（数据层骨架）
│   └── data/build.gradle.kts          ← Room / Hilt / DataStore 依赖
│
├── :app                               ← Android Application 主模块
│   └── app/src/main/java/com/znliang/committee/
│       ├── engine/                     ← 核心引擎层
│       │   ├── AgentPool.kt            ← LLM 调用池（实现 LlmClient 接口）
│       │   ├── LlmClient.kt           ← LLM 调用抽象接口
│       │   ├── StreamResult.kt         ← 类型安全的流式结果（sealed interface）
│       │   ├── LlmProvider.kt          ← 多提供商配置
│       │   ├── LlmContentBuilder.kt    ← 多模态内容构建
│       │   ├── sse/
│       │   │   └── SseParser.kt        ← SSE 解析器（Anthropic + OpenAI 共享）
│       │   ├── report/
│       │   │   └── DecisionReportGenerator.kt
│       │   └── runtime/                ← 会议运行时（v7 协作者模式）
│       │       ├── AgentRuntime.kt      ← 门面 + 状态持有（505 行）
│       │       ├── RuntimeContext.kt    ← 协作者共享接口
│       │       ├── MeetingOrchestrator.kt ← 主循环 + 评分 + 投票 + 评级
│       │       ├── HumanInteraction.kt  ← 人机交互（暂停/注入/跟进）
│       │       ├── PostMeetingReflector.kt ← 会后反思 + 进化
│       │       ├── Agent.kt / GenericAgent.kt ← Agent 接口 + Preset 驱动实现
│       │       ├── GenericSupervisor.kt ← 动态监督员
│       │       ├── EvolvableAgent.kt    ← 可进化 Agent 基类
│       │       ├── Blackboard.kt        ← 不可变共享黑板
│       │       ├── BoardTypes.kt        ← UnifiedResponse 解析
│       │       ├── ToolExecutor.kt      ← 工具执行抽象接口
│       │       ├── DynamicToolRegistry.kt ← Tool 注册 + 路由（实现 ToolExecutor）
│       │       ├── tools/
│       │       │   ├── BuiltinToolExecutor.kt ← web_search / web_extract
│       │       │   └── CustomToolExecutor.kt  ← http/llm/js/intent/db_query/chain/regex
│       │       ├── PromptEvolver.kt / SkillLibrary.kt / AgentSelfEvolver.kt
│       │       ├── SystemLlmService.kt  ← 系统级 LLM 调用
│       │       └── WebSearchService.kt
│       ├── domain/model/               ← 领域模型
│       │   ├── MeetingPreset.kt         ← 10+ 内置预设定义
│       │   ├── PresetRole.kt            ← 动态角色（取代硬编码 AgentRole 枚举）
│       │   ├── MeetingPresetConfig.kt   ← 预设配置管理
│       │   ├── PresetSkillCatalog.kt    ← 30 个推荐 Skill
│       │   ├── PresetRecommender.kt     ← 智能预设推荐
│       │   ├── MeetingState.kt          ← UI 层会议状态
│       │   └── Rating.kt / CommitteeEvent.kt / AppConfig.kt
│       ├── data/                       ← 数据持久化
│       │   ├── db/                      ← Room v4 SQLite（8 Entity）
│       │   │   ├── CommitteeDatabase.kt
│       │   │   ├── EventEntity.kt / EventDao.kt
│       │   │   ├── EvolutionEntities.kt / EvolutionDao.kt
│       │   │   ├── SkillDefinitionEntity.kt
│       │   │   ├── DecisionActionEntity.kt
│       │   │   ├── MeetingMaterialEntity.kt
│       │   │   └── AppConfigDao.kt
│       │   └── repository/              ← Repository 层
│       │       ├── EventRepository.kt
│       │       ├── EvolutionRepository.kt
│       │       ├── SkillRepository.kt
│       │       ├── ActionRepository.kt
│       │       └── AppConfigRepository.kt
│       ├── di/                         ← Hilt 依赖注入
│       │   ├── AppModule.kt
│       │   ├── DataStoreApiKeyProvider.kt ← Per-agent LLM 配置
│       │   └── KeystoreCipher.kt       ← API Key 加密
│       ├── ui/
│       │   ├── MainActivity.kt          ← 导航 + 路由
│       │   ├── model/
│       │   │   └── UiTypes.kt           ← UI 层值类型（VoteInfo/MaterialItem/ContributionInfo/UiPhase）
│       │   ├── screen/
│       │   │   ├── HomeScreen.kt        ← 首页编排层（416 行）
│       │   │   ├── home/                ← HomeScreen 拆分组件
│       │   │   │   ├── MeetingInitSection.kt    ← 会议发起区
│       │   │   │   ├── MeetingActiveSection.kt  ← 会议进行区
│       │   │   │   ├── MeetingSummarySection.kt ← 总结展示区
│       │   │   │   ├── VoteSection.kt           ← 投票区
│       │   │   │   └── ActionItemsSection.kt    ← 执行项区
│       │   │   ├── AgentsScreen.kt / HistoryScreen.kt / SessionDetailScreen.kt
│       │   │   ├── LogScreen.kt / SettingsScreen.kt
│       │   │   ├── MeetingConfigScreen.kt / ModelConfigScreen.kt
│       │   │   ├── SearchConfigScreen.kt / SkillManagementScreen.kt
│       │   ├── component/               ← 通用 Compose 组件
│       │   │   ├── CommitteeComponents.kt / FlowVizComponents.kt
│       │   │   ├── SpeechCard.kt / MarkdownText.kt
│       │   ├── viewmodel/
│       │   │   ├── MeetingViewModel.kt  ← 会议主 VM（Split State 架构）
│       │   │   ├── UiTypeMappers.kt     ← 引擎类型 → UI 类型映射
│       │   │   ├── FlowVizViewModel.kt / AgentChatViewModel.kt
│       │   │   ├── SettingsViewModel.kt / SkillManagementViewModel.kt
│       │   │   └── AgentMemoryViewModel.kt
│       │   └── theme/
│       └── assets/presets/              ← JSON 预设数据
│           ├── all_presets.json          ← 10 个预设定义
│           └── skill_catalog.json       ← 30 个推荐 Skill
│
├── app/src/test/                       ← 单元测试（141 tests）
│   ├── engine/runtime/
│   │   ├── GenericAgentTest.kt          ← 37 tests
│   │   ├── GenericSupervisorTest.kt     ← 27 tests
│   │   ├── MeetingOrchestratorTest.kt   ← 28 tests
│   │   ├── BlackboardTest.kt           ← 测试共识/阶段推断
│   │   └── UnifiedResponseParseTest.kt ← 测试响应解析
│   └── engine/sse/
│       └── SseParserTest.kt            ← SSE 解析测试
│
├── build.gradle.kts                    ← 根构建脚本
├── settings.gradle.kts                 ← include(:app, :core, :data)
└── gradle/libs.versions.toml           ← 版本目录
```

### 模块依赖关系

```
:app  →  :core, :data
:data →  :core
:core →  (无项目依赖，仅 kotlinx-coroutines, okhttp, gson)
```

### 层边界规则

- **UI 屏幕层** (`ui/screen/`, `ui/component/`) — 零 `engine.runtime` 导入
- **ViewModel 层** (`ui/viewmodel/`) — 通过 `UiTypeMappers` 将引擎类型转换为 UI 类型
- **引擎层** (`engine/`) — 通过 `LlmClient` / `ToolExecutor` 接口解耦，可纯 JVM 测试

---

## 核心机制

### 1. Preset 驱动的动态委员会

10+ 内置预设，每个预设定义角色、立场、职责、投票规则：

| 预设 | 角色数 | 投票类型 | 场景 |
|------|--------|----------|------|
| 投资委员会 | 6 | 6级评级 | 股票/基金投资决策 |
| 产品评审 | 5 | BINARY | 产品上线评审 |
| 技术方案评审 | 5 | SCALE | 架构方案决策 |
| 辩论赛 | 4 | MULTI_STANCE | 正反方辩论 |
| 合规审查 | 4 | BINARY | 法规合规检查 |
| 用户体验评审 | 4 | SCALE | UX 设计评审 |
| 安全评估 | 4 | BINARY | 安全风险评估 |
| 科研评审 | 5 | SCALE | 论文/实验评审 |
| 创业评估 | 4 | SCALE | 创业项目评估 |
| 招聘评估 | 4 | BINARY | 候选人评估 |

支持自定义预设：自定义角色、立场、prompt、工具、投票类型。

### 2. 自主决策（非规则调度）

Agent 通过 LLM 判断是否发言，不由系统强制调度。每轮流程：

```
eligible() 过滤 → scoring() 加权评分 → weightedRandom(topK) 选中
→ respond() 一次 LLM 调用返回 SPEAK/CONTENT/VOTE/TAGS
→ SPEAK=YES → 真流式输出（LLM delta 直通 UI）+ 写入 Blackboard
→ SPEAK=NO  → 跳过，advanceRound()
```

### 3. UnifiedResponse（shouldAct + act + vote 合一）

一次 LLM 调用同时返回：
- `SPEAK: YES/NO` — 是否发言
- `REASONING: ...` — 思考过程
- `CONTENT: ...` — 发言内容
- `VOTE: BULL/BEAR` 或 `VOTE: 7/10` 或 `VOTE: Approve` — 按投票类型
- `TAGS: ...` — 语义标签

LLM 调用次数 = 讨论轮次数，无额外开销。

### 4. Blackboard（不可变共享状态）

所有 Agent 共享一个 `Blackboard`（data class），包含：
- `messages` — 所有发言（不可变 List）
- `votes` — 投票记录（`Map<role, BoardVote>`，同 Agent 只保留最新票）
- `summary` — 定期由监督员生成的讨论摘要
- `contextForAgent()` — 按 Agent 的 attentionTags 过滤相关消息

每次更新创建新实例 → StateFlow 自动检测变化 → UI 更新。

**双阶段系统**：
- `BoardPhase`（引擎层，7 值）：`IDLE → ANALYSIS → DEBATE → VOTE → RATING → EXECUTION → DONE`
- `UiPhase`（UI 层镜像）：通过 `UiTypeMappers` 从引擎类型转换

### 5. 类型安全的流式管道

```kotlin
sealed interface StreamResult {
    data class Token(val text: String) : StreamResult
    data class Error(val type: ErrorType, val message: String) : StreamResult
    data object Done : StreamResult
}
```

全链路类型安全，无魔法字符串。`ErrorType` 枚举：`BILLING`, `NETWORK`, `RATE_LIMIT`, `UNKNOWN`。

### 6. AgentRuntime 协作者架构

AgentRuntime（505 行门面）将职责委托给三个协作者：

| 协作者 | 职责 | 行数 |
|--------|------|------|
| `MeetingOrchestrator` | 主循环、Agent 选择、流式收集、投票、评级 | ~790 |
| `HumanInteraction` | 暂停/恢复、注入消息/投票、跟进问题、覆盖决策 | ~220 |
| `PostMeetingReflector` | 会后反思、经验提取、Skill 学习、Prompt 进化 | ~220 |

三者通过 `RuntimeContext` 接口访问 AgentRuntime 的状态，无 inner class 耦合。

### 7. 动态工具系统

`DynamicToolRegistry` 管理两类工具：
- **内置工具**：`web_search`、`web_extract`（由 `BuiltinToolExecutor` 执行）
- **自定义工具**：用户通过 DB 定义的 http/llm/js/intent/db_query/chain/regex 工具（由 `CustomToolExecutor` 执行，含 SSRF/JS 沙箱/SQL 注入防护）

### 8. Split State UI 架构

ViewModel 将状态拆分为 4 个独立 StateFlow，减少不必要的重组：

| State | 内容 |
|-------|------|
| `StreamingState` | speeches, isRunning, isPaused, logs |
| `BoardState` | phase, votes, rating, summary, contributions, confidence |
| `ConfigState` | llmConfig, hasApiKey, error |
| `ActionState` | pendingActions, sessions |

### 9. 进化系统

- **会后反思**：每个参与过的 Agent 通过 LLM 反思 prompt 效果，生成优化建议
- **经验记忆**：`EvolvableAgent` 积累跨会议经验，影响后续决策
- **Skill 学习**：`SkillLibrary` 从讨论中提取可复用技能
- **Prompt 进化**：`PromptEvolver` 基于反思结果优化 prompt（不自动修改，供用户审批）

---

## 支持的 LLM

| 提供商 | 默认模型 | 备注 |
|--------|----------|------|
| Anthropic | claude-sonnet-4-20250514 | 原生 SSE |
| DeepSeek | deepseek-chat / deepseek-reasoner | OpenAI 兼容 |
| Kimi (Moonshot) | moonshot-v1-8k / kimi-k2.5 | OpenAI 兼容 |
| 自定义 | 任意 OpenAI 兼容 API | 自定义 baseUrl |

每个 Agent 可独立配置 provider/model/apiKey/baseUrl（DataStore 加密存储）。

---

## 技术栈

| 层 | 技术 |
|----|------|
| 架构 | Clean Architecture 多模块（:app / :core / :data） |
| UI | Jetpack Compose + Material3 (BOM 2024.08.00) |
| 导航 | Navigation Compose 2.7.7 |
| 状态 | StateFlow（Split State：4 个独立流） |
| DI | Hilt 2.51.1 |
| 数据库 | Room 2.6.1 SQLite（8 Entity） |
| 网络 | OkHttp 4.12.0 SSE（共享 SseParser） |
| 序列化 | Gson 2.11.0 |
| 异步 | Coroutines 1.8.1 |
| 配置 | DataStore Preferences 1.1.1 + Keystore 加密 |
| 测试 | JUnit 4 + MockK + kotlinx-coroutines-test（141 tests） |
| 构建 | AGP 8.5.2 + Kotlin 2.0 + KSP 2.0.0-1.0.22 + JVM 17 |

---

## 快速开始

### 1. 构建安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 需要 JDK 17。若系统默认非 17，可前置 `JAVA_HOME=...`。

### 2. 运行测试

```bash
./gradlew :app:testDebugUnitTest :core:test
```

### 3. 配置 LLM

进入 **设置** 页面：
- 选择 LLM 提供商
- 填入 API Key
- 选择模型

API Key 经 Keystore 加密后存储在设备本地 DataStore，不上传。

### 4. 发起会议

首页选择预设类型（投资委员会、产品评审、技术方案评审...），输入讨论主题，点击 **召开会议**。

---

## 数据持久化

Room v4 数据库，8 张表：
- `EventEntity` — 事件记录
- `MeetingSessionEntity` — 会议会话（含评级、状态）
- `SpeechEntity` — 发言记录（含 round、agent role）
- `AgentChatMessageEntity` — per-agent 聊天消息
- `AgentEvolutionEntity` / `AgentExperienceEntity` — Agent 进化与经验
- `SkillDefinitionEntity` — 自定义工具/技能
- `DecisionActionEntity` — 决策执行项
- `MeetingMaterialEntity` — 会议材料附件

支持会话恢复：App 重启后自动加载历史会议。
