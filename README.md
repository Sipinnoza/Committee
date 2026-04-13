# 投委会 App（Investment Committee）

> 多 Agent 自主决策投资分析系统。  
> Agent 通过 LLM 自行判断是否发言、何时发言，对抗性辩论产出投资评级。

---

## 架构总览（v5）

```
InvestmentCommittee/
├── app/src/main/java/com/committee/investing/
│   ├── engine/                          ← 核心引擎层
│   │   ├── AgentPool.kt                 ← LLM 调用池（流式 SSE，多提供商）
│   │   ├── LlmProvider.kt              ← LLM 提供商配置（Anthropic/DeepSeek/Kimi）
│   │   └── runtime/                     ← v5 自主决策运行时
│   │       ├── AgentRuntime.kt          ← 主循环：加权选择 → respond → 流式输出
│   │       ├── Agent.kt                 ← Agent 接口 + scoring() 加权评分
│   │       ├── Agents.kt                ← 6 个具体 Agent 实现
│   │       ├── Blackboard.kt            ← 共享黑板（不可变 data class）
│   │       └── SupervisorAgent.kt       ← 主席：结束判断/点评/评级/摘要
│   ├── domain/model/                    ← 领域模型
│   │   ├── MeetingState.kt              ← 会议状态枚举
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
│   └── ui/
│       ├── screen/                      ← 6 个页面
│       │   ├── HomeScreen.kt            ← 首页（发起会议）
│       │   ├── AgentsScreen.kt          ← Agent 管理 + per-agent 聊天
│       │   ├── HistoryScreen.kt         ← 会议历史
│       │   ├── SessionDetailScreen.kt   ← 历史详情
│       │   ├── LogScreen.kt             ← 运行日志
│       │   └── SettingsScreen.kt        ← LLM 配置
│       ├── component/                   ← Compose 组件
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
→ SPEAK=YES → 流式输出 + 写入 Blackboard
→ SPEAK=NO  → 跳过，advanceRound()
```

### 2. UnifiedResponse（shouldAct + act + vote 合一）

一次 LLM 调用同时返回：
- `SPEAK: YES/NO` — 是否发言
- `CONTENT: ...` — 发言内容（200 字以内）
- `VOTE: BULL/BEAR` — 投票
- `TAGS: ...` — 语义标签

LLM 调用次数 = 讨论轮次数，无额外开销。

### 3. Blackboard（不可变共享状态）

所有 Agent 共享一个 `Blackboard`（data class），包含：
- `messages` — 所有发言（不可变 List）
- `votes` — 投票记录
- `summary` — 每 2 轮由 Supervisor 生成的讨论摘要
- `contextForAgent()` — 按 Agent 的 attentionTags 过滤相关消息

每次更新创建新实例 → StateFlow 自动检测变化 → UI 更新。

### 4. 加权选择（Weighted Selection）

每个 Agent 有 `scoring()` 评分函数，考虑：
- 距上次发言的轮数（越久没说分越高）
- 最近消息与 attentionTags 的匹配度
- 是否存在分歧（多空接近）
- 本轮是否已发言（降分）

取 top-K=2，加权随机选择，避免固定顺序。

### 5. 稀疏激活

每轮只激活 K=2 个 Agent（非全员调用），降低 LLM 成本。

### 6. Supervisor 降频

- **结束判断**：每 2 轮或第 4 轮起，问 Supervisor 是否可以评级
- **点评**：仅在非共识 + 有 3 条以上非 supervisor 发言时触发
- **摘要**：每 2 轮生成一次 Summary Memory，后续 Agent 可引用摘要

### 7. 会后自反思

会议结束后，每个参与过的 Agent 通过 LLM 反思自己的 prompt 效果，生成优化建议（不自动修改，供用户审批）。

---

## 6 大角色

| 角色 | 立场 | 职责 | 首轮行为 |
|------|------|------|----------|
| 分析师 | 看多 Bull | 基本面/估值/催化剂分析 | 强制发言 |
| 风险官 | 看空 Bear | 财务/行业/宏观风险 | 强制发言 |
| 策略师 | 中立 | 多空权衡/仓位/风险收益比 | 等多空发言后再参与 |
| 情报官 | 事实 | 市场情报/价格/新闻/行业动态 | 强制发言 |
| 执行员 | 方案 | 执行计划/操作方向/止损止盈 | 有评级后参与 |
| 主席 | 评判 | 结束判断/点评/评级/摘要 | 降频参与 |

---

## 会议流程

```
IDLE ──[发起]──▶ 循环讨论（最多 20 轮）
                  │
                  ├─ 每 2 轮：Supervisor 结束判断
                  ├─ 每 2 轮：Summary Memory 更新
                  ├─ 每轮：加权选择 K=2 个 Agent
                  │    └─ Agent 自主决定 SPEAK YES/NO
                  │    └─ YES → 流式输出 + 投票 + 标签
                  │
                  ▼
              RATING（Supervisor 发布评级）
                  │
                  ▼
              EXECUTION（执行员制定方案）
                  │
                  ▼
              DONE
```

---

## 支持的 LLM

| 提供商 | 默认模型 | 备注 |
|--------|----------|------|
| Anthropic | claude-sonnet-4-20250514 | 原生 SSE |
| DeepSeek | deepseek-chat | OpenAI 兼容 |
| Kimi (Moonshot) | moonshot-v1-8k | OpenAI 兼容，支持 kimi-k2.5 |

每个 Agent 可独立配置 provider/model/apiKey/baseUrl（DataStore 存储）。

---

## 技术栈

| 层 | 技术 |
|----|------|
| UI | Jetpack Compose + Material3 |
| 导航 | Navigation Compose |
| 状态 | StateFlow（不可变 data class） |
| DI | Hilt |
| 数据库 | Room SQLite v4（4 Entity） |
| 网络 | OkHttp3 SSE（手写流式解析） |
| 配置 | DataStore Preferences |
| 构建 | AGP 8.5.2 + Kotlin 2.0 + KSP + Java 17 |

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
