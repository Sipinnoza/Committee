# 投委会 App（Investment Committee）

> 基于 Committee Looper v5.2 规格构建的 Android 原生应用。  
> 多 Agent 对抗性投资决策系统，Bull vs Bear，结构化辩论产出可靠投资评级。

---

## 架构总览

```
InvestmentCommittee/
├── app/src/main/java/com/committee/investing/
│   ├── engine/                     ← 核心引擎层
│   │   ├── CommitteeLooper.kt      ← 主事件循环（类比 Android Looper）
│   │   ├── StateEngine.kt          ← 确定性 FSM（14条转换规则）
│   │   ├── Scheduler.kt            ← 调度器（once/parallel/round_robin）
│   │   ├── AgentPool.kt            ← LLM Agent 调用池（6个角色）
│   │   ├── TimerRegistry.kt        ← 超时定时器注册表
│   │   └── IdempotencyGuard.kt     ← 幂等性守卫
│   ├── domain/model/               ← 领域模型
│   │   ├── MeetingState.kt         ← 状态机枚举 + FSM 转换规则
│   │   ├── AgentRole.kt            ← 六大角色定义
│   │   ├── CommitteeEvent.kt       ← 事件 + 会话模型（事件溯源）
│   │   └── Rating.kt               ← 6级评级体系
│   ├── data/
│   │   ├── db/                     ← Room SQLite（替代 events.jsonl）
│   │   ├── remote/                 ← Anthropic API（Retrofit）
│   │   └── repository/             ← EventRepository（事件溯源 + 恢复）
│   ├── di/                         ← Hilt 依赖注入
│   └── ui/
│       ├── screen/                 ← 4个页面（会议/历史/日志/设置）
│       ├── component/              ← 复用 Compose 组件
│       ├── theme/                  ← 深色主题 + 委员会金色
│       └── viewmodel/              ← MeetingViewModel（单一状态流）
```

---

## 与规格文档的对应关系

| 规格文档                                         | Android 实现                                                           |
|----------------------------------------------|----------------------------------------------------------------------|
| `Looper.loop()`                              | `CommitteeLooper.processLoop()`                                      |
| `MessageQueue (events.jsonl)`                | `Channel<CommitteeEvent>` + Room SQLite                              |
| `StateEngine` (§4.2)                         | `StateEngine.kt` — 纯 FSM                                             |
| `Scheduler` once/parallel/round_robin (§4.3) | `Scheduler.kt`                                                       |
| `Agent Pool` (§9.4)                          | `AgentPool.kt` — litellm → Anthropic API                             |
| `IdempotencyGuard` (§1.1)                    | `IdempotencyGuard.kt`                                                |
| `TimerRegistry` (§3.2)                       | `TimerRegistry.kt` — `asyncio.wait_for` → `kotlinx coroutines delay` |
| `looper-state.json` (§8.2)                   | `MeetingSessionEntity.stateJson` in SQLite                           |
| 恢复流程 (§8.3)                                  | `CommitteeLooper.recover()` + `EventRepository.replayState()`        |
| 6级评级 (§1.3)                                  | `Rating.kt`                                                          |
| 14条 FSM 转换 (§2.1)                            | `MeetingState.TRANSITIONS`                                           |

---

## 快速开始

### 1. 配置 SDK 路径

```bash
cp local.properties.template local.properties
# 编辑 local.properties，填入你的 Android SDK 路径
```

### 2. 用 Android Studio 打开

打开 Android Studio → Open → 选择 `InvestmentCommittee/` 目录。

### 3. 配置 API Key

运行 App 后，进入 **设置** 页面，填入 Anthropic API Key（格式：`sk-ant-...`）。  
Key 仅存储在设备本地 DataStore，不上传。

### 4. 发起第一场会议

在 **会议** 页面输入标的代码（如 `600028 石化`），点击 **召开会议**。

---

## 会议流程

```
idle ──[发起]──▶ validating（策略师入场评估，120s）
              ──▶ prepping（四路并行准备，300s）
              ──▶ phase1_debate（Bull/Bear/策略师轮替，最多8轮，每轮600s）
              ──▶ phase2_assessment（执行员+风险官，最多6轮）
              ──▶ final_rating（执行员发布评级，60s）
              ──▶ approved（用户确认执行）
              ──▶ completed（监督员写纪要）
              ──▶ idle
```

任何阶段可随时 **取消会议**（→ idle）。

---

## 技术栈

| 层   | 技术                                          |
|-----|---------------------------------------------|
| UI  | Jetpack Compose + Material3                 |
| 导航  | Navigation Compose                          |
| 状态  | StateFlow + SharedFlow                      |
| DI  | Hilt                                        |
| 数据库 | Room SQLite                                 |
| 网络  | Retrofit2 + OkHttp3                         |
| LLM | Anthropic Claude (claude-sonnet-4-20250514) |
| 配置  | DataStore Preferences                       |
| 构建  | AGP 8.5.2 + Kotlin 2.0 + KSP                |

---

## 核心不变量（规格文档 §11）

1. **Looper 是唯一调度者** — Agent 永远不能自己决定何时发言
2. **事件溯源** — 所有状态变化来自事件流，可完整重放
3. **幂等处理** — 同一事件处理多次无副作用
4. **确定性** — 同样的事件流 = 同样的调度序列
5. **配置驱动** — `Scheduler.PHASE_CONFIG` 集中管理所有调度规则
6. **对抗结构** — Bull vs Bear，强制双方找盲点

---

## 扩展方向

- **多模型支持**：修改 `AgentPool.kt` 中的 `model` 参数，或接入 GLM/DeepSeek
- **自定义提示词**：每个角色的 `buildSystemPrompt()` 独立，可按需调整
- **WebSocket 实时推送**：将 `CommitteeLooper.looperLog` 的 SharedFlow 桥接到 WebSocket
- **持仓管理**：新增 `position.json` 对应的 `PositionEntity` + `PositionDao`
