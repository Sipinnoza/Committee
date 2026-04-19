# 多Agent会议 App

> 多 Agent 自主讨论决策系统。  
> Agent 通过 LLM 自行判断是否发言、何时发言，对抗性辩论产出决策建议。

---

## 架构总览（v6）

```
InvestmentCommittee/
├── app/src/main/java/com/committee/investing/
│   ├── engine/                          ← 核心引擎层
│   │   ├── AgentPool.kt                 ← LLM 调用池（真流式 SSE，多提供商）
│   │   ├── LlmProvider.kt              ← LLM 提供商配置（Anthropic/DeepSeek/Kimi）
│   │   └── runtime/                     ← v6 自主决策运行时
│   │       ├── AgentRuntime.kt          ← 主循环：加权选择 → respond → 真流式输出
│   │       ├── Agent.kt                 ← Agent 接口 + scoring() 加权评分
│   │       ├── Agents.kt                ← 5 个具体 Agent 实现
│   │       ├── Blackboard.kt            ← 共享黑板（不可变 data class）+ BoardPhase + MsgTag
│   │       ├── SupervisorAgent.kt       ← 监督员：结束判断/点评/评级/摘要
│   │       └── SystemLlmService.kt      ← 系统级 LLM 调用（Supervisor/反思专用）
│   ├── domain/model/                    ← 领域模型
│   │   ├── MeetingState.kt              ← UI 层会议状态（10 个枚举值）
│   │   ├── AgentRole.kt                 ← 6 大角色定义
│   │   ├── CommitteeEvent.kt            ← 事件 + 会话模型
│   │   └── Rating.kt                    ← 6 级评级体系
│   ├── data/
│   │   ├── db/                          ← Room v4 SQLite
│   │   │   ├── CommitteeDatabase.kt     ← DB 定义（4 Entity）
│   │   │   ├── EventEntity.kt           ← 事件 / 会话 / 发言 / 聊天
│   │   │   └── EventDao.kt              ← DAO
│   │   ├── remote/LlmApiService.kt      ← API 接口
│   │   └── repository/EventRepository.kt← 持久化 + 恢复
│   ├── di/                              ← Hilt 依赖注入
│   │   ├── AppModule.kt                 ← Agent 实例化 + 依赖提供
│   │   └── DataStoreApiKeyProvider.kt   ← Per-agent LLM 配置（DataStore）
│   ├── CommitteeApplication.kt          ← @HiltAndroidApp 入口
│   └── ui/
│       ├── MainActivity.kt              ← 5 Tab 导航 + 路由
│       ├── screen/                      ← 6 个页面
│       │   ├── HomeScreen.kt            ← 首页（发起会议）
│       │   ├── AgentsScreen.kt          ← Agent 管理 + per-agent 聊天
│       │   ├── HistoryScreen.kt         ← 会议历史
│       │   ├── SessionDetailScreen.kt   ← 历史详情
│       │   ├── LogScreen.kt             ← 运行日志
│       │   └── SettingsScreen.kt        ← LLM 配置
│       ├── component/                   ← Compose 组件
│       │   ├── CommitteeComponents.kt   ← 通用组件（AgentChip/RatingBadge/StateBadge 等）
│       │   ├── FlowVizComponents.kt     ← 会议流程可视化
│       │   ├── SpeechCard.kt            ← 发言卡片（流式打字效果）
│       │   └── MarkdownText.kt          ← Markdown 渲染
│       ├── theme/                       ← 深色主题 + 委员会金色
│       └── viewmodel/                   ← ViewModel
│           ├── MeetingViewModel.kt      ← 会议主 VM
│           ├── FlowVizViewModel.kt      ← 流程可视化 VM
│           └── AgentChatViewModel.kt    ← per-agent 聊天 VM
```

---

## 核心机制

### 1. 自主决策（非规则调度）

Agent 通过 LLM 判断是否发言，不由系统强制调度。每轮流程：

```
eligible() 过滤 → scoring() 加权评分 → weightedRandom(topK=2) 选中
→ respond() 一次 LLM 调用返回 SPEAK/CONTENT/VOTE/TAGS
→ SPEAK=YES → 真流式输出（LLM delta 直通 UI）+ 写入 Blackboard
→ SPEAK=NO  → 跳过，advanceRound()
```

### 2. UnifiedResponse（shouldAct + act + vote 合一）

一次 LLM 调用同时返回：
- `SPEAK: YES/NO` — 是否发言
- `CONTENT: ...` — 发言内容（200 字以内）
- `VOTE: BULL/BEAR` — 投票
- `TAGS: ...` — 语义标签（自动归一化为 MsgTag 枚举）

LLM 调用次数 = 讨论轮次数，无额外开销。

### 3. Blackboard（不可变共享状态）

所有 Agent 共享一个 `Blackboard`（data class），包含：
- `messages` — 所有发言（不可变 List）
- `votes` — 投票记录（`Map<role, BoardVote>`，同 Agent 只保留最新票）
- `summary` — 每 2 轮由监督员生成的讨论摘要
- `contextForAgent()` — 按 Agent 的 attentionTags 过滤相关消息

每次更新创建新实例 → StateFlow 自动检测变化 → UI 更新。

**双状态系统**：
- `BoardPhase`（引擎层，7 值）：`IDLE → ANALYSIS → DEBATE → VOTE → RATING → EXECUTION → DONE`
- `MeetingState`（UI 层，10 值）：更细粒度的阶段（含 VALIDATING/REJECTED/PREPPING/ADJUDICATING/ASSESSMENT）

### 4. MsgTag 语义标签

消息标签枚举，用于 attentionTags 匹配和加权评分：
`BULL, BEAR, RISK, VALUATION, GROWTH, NEWS, STRATEGY, EXECUTION, TECHNICAL, GENERAL`

### 5. 加权选择（Weighted Selection）

每个 Agent 有 `scoring()` 评分函数，考虑：
- 距上次发言的轮数（越久没说分越高）
- 最近消息与 attentionTags 的匹配度
- 是否存在分歧（多空接近）
- 本轮是否已发言（降分 -3）

取 top-K=2，加权随机选择，避免固定顺序。

### 6. 稀疏激活

每轮只激活 K=2 个 Agent（非全员调用），降低 LLM 成本。

### 7. 监督员降频

- **结束判断**：每 2 轮或第 4 轮起，问监督员是否可以评级
- **点评**：共识未达成 + 非 supervisor 发言 ≥ 3 条时触发
- **摘要**：每 2 轮生成一次 Summary Memory，后续 Agent 可引用摘要

### 8. SystemLlmService

系统级 LLM 调用服务，与 Agent 调用分离。用于监督员的结束判断、点评、评级、摘要，以及会后反思。通过 `AgentPool.callSystemStreaming()` 调用，无 Agent 身份。

### 9. 会后自反思

会议结束后，每个参与过的 Agent 通过 LLM 反思自己的 prompt 效果，生成优化建议（不自动修改，供用户审批）。

---

## 6 大角色

| 角色 | 立场 | 职责 | 首轮行为 |
|------|------|------|----------|
| 分析师 | 看多 Bull | Bull Case + 估值框架 + 前次预测回顾 | 强制发言 |
| 风险官 | 看空 Bear | Bear Case + 风险日历 + 质疑 | 强制发言 |
| 策略师 | 中立/框架 | Top-down 策略框架 + 入场评估 + 跨会议一致性 | 入场评估 |
| 情报员 | 事实 | 基础情报 + 增量推送 | 强制发言 |
| 执行员 | 方案 | 执行方案 + 评级 + 执行追踪 | 有评级后参与 |
| 监督员 | 评判 | 仲裁 + 纪要 + 执行纪律追踪 | 降频参与 |

---

## 会议流程

```
IDLE ──[发起]──▶ VALIDATING（策略师入场评估）
                    │
                    ├─ REJECTED（不适格，会议终止）
                    ▼
                PREPPING（四路并行准备）
                    │
                    ▼
                PHASE1_DEBATE（最多 20 轮循环）
                    │
                    ├─ 每 2 轮：监督员结束判断
                    ├─ 每 2 轮：Summary Memory 更新
                    ├─ 每轮：加权选择 K=2 个 Agent
                    │    └─ Agent 自主决定 SPEAK YES/NO
                    │    └─ YES → 真流式输出 + 投票 + 标签
                    │
                    ├─ 达成共识或监督员判结 ──▶ FINAL_RATING
                    └─ 轮次用尽 ──▶ PHASE1_ADJUDICATING（监督员仲裁）──▶ FINAL_RATING
                                                                       │
                                                               PHASE2_ASSESSMENT（风险评估）
                                                                       │
                                                                    APPROVED
                                                                       │
                                                                    COMPLETED
```

### MeetingState 完整枚举

| 枚举 | 显示名 | 说明 |
|------|--------|------|
| `IDLE` | 待机 | 等待发起新会议 |
| `VALIDATING` | 入场评估 | 策略师检查标的适格性 |
| `REJECTED` | 已拒绝 | 入场评估未通过 |
| `PREPPING` | 并行准备 | 四路并行准备中 |
| `PHASE1_DEBATE` | 多方辩论 | Bull vs Bear 辩论 |
| `PHASE1_ADJUDICATING` | 监督员仲裁 | 轮次用尽，监督员裁决 |
| `PHASE2_ASSESSMENT` | 风险评估 | 执行员提方案，风险官挑战 |
| `FINAL_RATING` | 发布评级 | 发布最终评级与执行方案 |
| `APPROVED` | 已批准 | 等待用户确认执行 |
| `COMPLETED` | 会议完成 | 会议结束 |

---

## 支持的 LLM

| 提供商 | 默认模型 | 备注 |
|--------|----------|------|
| Anthropic | claude-sonnet-4-20250514 | 原生 SSE |
| DeepSeek | deepseek-chat / deepseek-reasoner | OpenAI 兼容 |
| Kimi (Moonshot) | moonshot-v1-8k / kimi-k2.5 | OpenAI 兼容 |

每个 Agent 可独立配置 provider/model/apiKey/baseUrl（DataStore 存储）。

---

## 技术栈

| 层 | 技术 |
|----|------|
| UI | Jetpack Compose + Material3 (BOM 2024.08.00) |
| 导航 | Navigation Compose 2.7.7 |
| 状态 | StateFlow（不可变 data class） |
| DI | Hilt 2.51.1 |
| 数据库 | Room 2.6.1 SQLite v4（4 Entity） |
| 网络 | OkHttp 4.12.0 SSE + Retrofit 2.11.0 |
| 序列化 | Gson 2.11.0 |
| 异步 | Coroutines 1.8.1 |
| 配置 | DataStore Preferences 1.1.1 |
| 构建 | AGP 8.5.2 + Kotlin 2.0 + KSP 2.0.0-1.0.22 + Java 17 |

---

## 快速开始

### 1. 构建安装

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home \
  ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 配置 LLM

进入 **设置** 页面：
- 选择 LLM 提供商
- 填入 API Key
- 选择模型

API Key 仅存储在设备本地 DataStore，不上传。

### 3. 发起会议

首页输入标的代码（如 `600028`），点击 **召开会议**。

---

## 数据持久化

Room v4 数据库，4 张表：
- `EventEntity` — 事件记录
- `MeetingSessionEntity` — 会议会话（含评级、状态）
- `SpeechEntity` — 发言记录（含 round、agent role）
- `AgentChatMessageEntity` — per-agent 聊天消息

支持会话恢复：App 重启后自动加载历史会议。
