# Hermes Agent 超级智能体通用设计模式

> 基于 /tmp/web_tools.py、/tmp/registry.py、hermes-dashboard.html 及 openclaw 运行日志分析

---

## 1. 工具系统：自注册 + 动态发现 (Self-Registering Tool Registry)

**源码**: `registry.py`

### 核心模式
- **模块级自注册**: 每个 tool 文件在 `import` 时调用 `registry.register()` 声明自己的 schema、handler、toolset 归属、可用性检查函数
- **单例注册中心**: `ToolRegistry` 全局单例，线程安全 (RLock)，支持快照读取
- **动态生命周期**: 支持 `register()` / `deregister()`，MCP 工具可热替换
- **可用性检查**: 每个 toolset 有 `check_fn`，运行时动态判断是否可用（如 API key 是否存在）
- **统一调度**: `dispatch(name, args)` 统一入口，自动桥接 async handler，统一错误格式

### 映射到 InvestmentCommittee
每个 IC agent 应：
- 拥有自己的 tool registry，按角色注册专属工具（如 analyst 注册 market_data_tool）
- 工具可用性由配置/凭证动态决定
- 支持运行时增减工具（如新数据源上线）

---

## 2. 记忆系统：分层记忆 + 梦境整合 (Layered Memory + Dream Consolidation)

**源码**: openclaw 运行日志

### 核心模式
Hermes 的 memory-core 插件实现了仿生记忆管理：

| 阶段 | 行为 | 类比 |
|------|------|------|
| **Memory Trace** | 每次交互产生 trace 记录 | 短期记忆 |
| **Light Dreaming** | 定期扫描 trace，提取 candidate memories | 浅睡眠整理 |
| **REM Dreaming** | 对近期 traces 批量反思，写入 dream diary | REM 深度整合 |
| **Dream Diary** | 持久化的反思日志 | 长期记忆 |
| **Recall Store** | 标准化后的召回存储 | 工作记忆索引 |

### 关键特性
- **多工作区隔离**: 每个 agent（analyst, risk_officer, executor, supervisor 等）有独立 workspace 和记忆
- **定时触发**: 按计划自动执行 dreaming（如每 30 分钟 light dreaming）
- **批量处理**: 单次 REM dreaming 处理数百条 trace（如 analyst 处理 333 条）
- **容量管理**: Dashboard 显示 memory 用量百分比（81%），有上限控制

### 映射到 InvestmentCommittee
每个 IC agent 应：
- 短期记忆：当前分析 session 的上下文
- 长期记忆：历史决策、市场规律、用户偏好
- 梦境整合：定期回顾历史决策，提炼投资原则
- 独立记忆空间：每个 agent 有自己的记忆分区

---

## 3. 技能系统：可扩展的能力包 (Skill System)

**源码**: hermes-dashboard.html

### 核心模式
- **分类技能树**: 86 个 skill 分 19 个类别（research, devops, mlops 等）
- **SKILL.md 格式**: YAML frontmatter + Markdown，定义 name/description/tags + 使用场景/实现方式
- **动态安装**: Skill Builder UI 允许创建和安装新 skill
- **技能搜索**: 支持按名称/分类搜索过滤
- **脚本绑定**: skill 可关联可执行脚本（Node.js/Python/Bash）

### 关键类别示例
- `research`: arxiv, blogwatcher, llm-wiki, polymarket
- `software-development`: plan, tdd, systematic-debugging, subagent-driven-development
- `mlops`: fine-tuning, serving, quantization, evaluation
- `creative`: ideation, architecture-diagram, video generation
- `devops`: investment-committee-fsm, webhook-subscriptions

### 映射到 InvestmentCommittee
每个 IC agent 应：
- 拥有角色特定的 skill 集合（如 analyst 有 DCF 估值、技术分析 skill）
- skill 定义标准化（名称、描述、适用场景、实现脚本）
- 支持运行时添加新 skill（如新的分析框架）

---

## 4. 智能内容处理：LLM 增强的信息压缩 (LLM-Augmented Content Processing)

**源码**: `web_tools.py` - `process_content_with_llm()`

### 核心模式
```
原始内容 → 阈值判断 → 分块策略选择 → 并行处理 → 合成汇总
```

**分治策略**:
- < 5K chars: 跳过处理，原样返回
- 5K-500K chars: 单次 LLM 总结
- 500K-2M chars: 分块并行总结 + 合成
- > 2M chars: 拒绝处理，要求缩小范围

**关键设计**:
- **LLM 辅助**: 使用 auxiliary LLM (Gemini Flash) 做智能摘要
- **并行分块**: `asyncio.gather()` 并行处理各 chunk
- **合成阶段**: 将多个 chunk 摘要合成为一份统一摘要
- **指数退避重试**: 失败自动重试，带 backoff
- **优雅降级**: LLM 失败时返回截断原文而非报错
- **Token 管理**: 硬性输出上限 (5000 chars)

### 映射到 InvestmentCommittee
每个 IC agent 应：
- 面对大量市场数据时自动分块处理
- 用 LLM 做智能摘要，保留关键数字和洞察
- 失败时有降级策略（返回原始数据片段）

---

## 5. 多后端抽象：配置驱动的能力切换 (Multi-Backend Abstraction)

**源码**: `web_tools.py` - `_get_backend()`

### 核心模式
```python
# 配置优先 → 环境探测 → 默认回退
configured = config.get("backend")
if configured in valid_backends:
    return configured
# fallback: 检查哪个 API key 存在
for backend, available in candidates:
    if available:
        return backend
return "firecrawl"  # default
```

**特性**:
- 支持 4 种后端: Exa / Firecrawl / Tavily / Parallel
- 惰性初始化 (lazy init): 客户端按需创建，全局缓存
- 配置热切换: 通过 config.yaml 或环境变量
- 结果格式归一化: 不同后端统一为 `{success, data: {web: [...]}}`

### 映射到 InvestmentCommittee
每个 IC agent 应：
- 数据源多后端支持（Bloomberg / Wind / Yahoo Finance / Tushare）
- 配置驱动切换，有回退链
- 数据格式统一归一化

---

## 6. 自我反思与进化 (Self-Reflection & Self-Evolution)

### 从代码和日志推断的模式

**自我反思**:
- Dream diary 写入反思条目（"REM dreaming wrote reflections from N traces"）
- 每个反思基于近期交互痕迹，提炼规律性认知
- Memory 条目包含经验教训（如 "write_file 过滤敏感模式"）

**自我进化**:
- Skill 系统允许动态添加能力
- Memory 系统持续积累知识
- 工具注册支持运行时增删

### 映射到 InvestmentCommittee
每个 IC agent 应：
- **决策反思**: 每次投资建议后自动评估准确性
- **策略进化**: 基于历史表现调整分析权重和参数
- **知识积累**: 将市场规律存入长期记忆

---

## 7. 多工作区协作 (Multi-Workspace Orchestration)

**源码**: openclaw 日志

### 核心模式
Hermes 同时运行多个 agent workspace:
- `analyst` — 分析师 (333 traces → REM dreaming)
- `risk_officer` — 风控官 (114 traces)
- `executor` — 执行者 (164 traces)
- `supervisor` — 主管 (240 traces)
- `strategy_validator` — 策略验证 (75 traces)
- `intel` — 情报 (189 traces)
- `creative` — 创意 (59 traces)
- `bob` — 通用 (2406 traces)

**特征**:
- 每个 workspace 独立记忆和 dreaming
- 统一的 dreaming 调度器管理所有 workspace
- 完成后汇报汇总: "dreaming promotion complete (workspaces=8, candidates=0, applied=0)"

### 映射到 InvestmentCommittee
IC 架构已天然对应:
- 每个投资委员会成员 = 一个 workspace
- 独立思考 + 共享会议机制
- 统一调度层管理各 agent

---

## 8. 通用能力清单（每个 IC Agent 应具备）

| 能力 | Hermes 实现 | IC Agent 映射 |
|------|------------|---------------|
| **工具使用** | ToolRegistry + 多后端 | 市场数据/财报/新闻 API |
| **记忆管理** | Memory trace + Dreaming | 短期上下文 + 长期经验 |
| **自我反思** | Dream diary + REM | 决策复盘 + 偏差检测 |
| **自我进化** | Skill 动态安装 | 新分析框架学习 |
| **内容压缩** | LLM 摘要 + 分块 | 研报/数据智能摘要 |
| **错误恢复** | 重试 + 降级 | 数据源故障切换 |
| **多后端** | 配置驱动切换 | 多数据源统一接口 |
| **容量管理** | Token/Size 限制 | 上下文窗口管理 |
| **并发处理** | asyncio.gather | 并行获取多资产数据 |
| **线程安全** | RLock + 快照 | 多 agent 并发访问 |

---

## 9. 架构图

```
┌─────────────────────────────────────────────────────┐
│                   Super Agent Pattern                │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │  Memory   │  │  Skills  │  │  Tool Registry   │  │
│  │ ──────── │  │ ──────── │  │ ──────────────── │  │
│  │ Traces   │  │ SKILL.md │  │ Self-Register    │  │
│  │ Light    │  │ Scripts  │  │ Multi-Backend    │  │
│  │ REM      │  │ Search   │  │ Availability Chk │  │
│  │ Diary    │  │ Builder  │  │ Dynamic Lifecycle│  │
│  └────┬─────┘  └────┬─────┘  └────────┬─────────┘  │
│       │             │                 │              │
│  ┌────┴─────────────┴─────────────────┴──────────┐  │
│  │              Agent Core Loop                   │  │
│  │  Observe → Think → Act → Reflect → Evolve     │  │
│  └──────────────────┬────────────────────────────┘  │
│                     │                               │
│  ┌──────────────────┴────────────────────────────┐  │
│  │           LLM-Augmented Processing            │  │
│  │  Content → Chunk → Summarize → Synthesize     │  │
│  │  Retry + Fallback + Token Management          │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```
