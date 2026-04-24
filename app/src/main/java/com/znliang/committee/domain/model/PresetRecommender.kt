package com.znliang.committee.domain.model

/**
 * 基于输入议题关键词的预设模板智能推荐器。
 * 通过关键词匹配 + 权重评分，为"团队决策"场景推荐最合适的会议模板。
 */
object PresetRecommender {

    private data class Rule(val presetId: String, val keywords: Set<String>, val weight: Int = 1)

    private val rules = listOf(
        // 投资 / 金融
        Rule("investment_committee", setOf(
            "股票", "stock", "基金", "fund", "投资", "invest", "估值", "valuation",
            "买入", "卖出", "增持", "减持", "持有", "buy", "sell", "hold",
            "上市", "ipo", "财报", "earnings", "分红", "dividend",
            "债券", "bond", "期货", "futures", "etf", "指数", "index",
            "a股", "港股", "美股", "纳斯达克", "nasdaq", "标普", "s&p",
            "市盈率", "pe", "市净率", "pb", "roe", "eps",
        ), weight = 2),
        // 产品评审
        Rule("product_review", setOf(
            "产品", "product", "功能", "feature", "需求", "requirement",
            "用户", "user", "体验", "ux", "ui", "设计", "design",
            "版本", "version", "迭代", "iteration", "上线", "launch", "发布", "release",
            "mvp", "prd", "原型", "prototype", "竞品", "competitor",
        ), weight = 2),
        // 技术评审
        Rule("tech_review", setOf(
            "架构", "architecture", "技术", "tech", "方案", "solution",
            "api", "接口", "性能", "performance", "安全", "security",
            "数据库", "database", "微服务", "microservice", "重构", "refactor",
            "代码", "code", "系统", "system", "部署", "deploy", "服务器", "server",
            "算法", "algorithm", "缓存", "cache", "并发", "concurrent",
        ), weight = 2),
        // 辩论
        Rule("debate", setOf(
            "辩论", "debate", "正方", "反方", "支持", "反对",
            "利弊", "pros", "cons", "是否", "should", "应该",
            "观点", "opinion", "立场", "position", "争议", "controversial",
        ), weight = 2),
        // 论文审稿
        Rule("paper_review", setOf(
            "论文", "paper", "审稿", "review", "学术", "academic",
            "研究", "research", "实验", "experiment", "方法论", "methodology",
            "引用", "citation", "期刊", "journal", "会议", "conference",
            "摘要", "abstract", "假设", "hypothesis",
        ), weight = 2),
        // 创业路演
        Rule("startup_pitch", setOf(
            "创业", "startup", "融资", "funding", "pitch", "路演",
            "种子轮", "seed", "a轮", "天使", "angel", "vc",
            "商业计划", "business plan", "估值", "valuation",
            "创始人", "founder", "投资人", "investor",
        ), weight = 2),
        // 合规审查
        Rule("legal_review", setOf(
            "合规", "compliance", "法律", "legal", "法务", "法规",
            "审查", "review", "监管", "regulation", "条款", "clause",
            "合同", "contract", "隐私", "privacy", "gdpr", "许可",
        ), weight = 2),
        // 事故复盘
        Rule("incident_postmortem", setOf(
            "事故", "incident", "复盘", "postmortem", "故障", "outage",
            "宕机", "downtime", "根因", "root cause", "rca",
            "回滚", "rollback", "告警", "alert", "oncall",
        ), weight = 2),
        // 头脑风暴
        Rule("brainstorm", setOf(
            "头脑风暴", "brainstorm", "创意", "creative", "想法", "idea",
            "脑暴", "发散", "diverge", "灵感", "inspiration",
            "创新", "innovation", "点子", "concept",
        ), weight = 2),
        // 通用会议 — 低权重兜底
        Rule("general_meeting", setOf(
            "会议", "meeting", "讨论", "discuss", "评审", "plan", "计划",
            "项目", "project", "团队", "team", "协作", "collaborate",
            "方案", "proposal", "决策", "decision", "决定",
        ), weight = 1),
    )

    /**
     * 根据议题文本推荐最佳预设。
     * @return 推荐的 presetId，如果没有明显匹配则返回 null
     */
    fun recommend(topic: String): String? {
        if (topic.length < 2) return null
        val lower = topic.lowercase()
        val scores = mutableMapOf<String, Int>()
        for (rule in rules) {
            val hits = rule.keywords.count { kw -> lower.contains(kw) }
            if (hits > 0) {
                scores[rule.presetId] = (scores[rule.presetId] ?: 0) + hits * rule.weight
            }
        }
        if (scores.isEmpty()) return null
        val best = scores.maxByOrNull { it.value } ?: return null
        // 至少 2 分才推荐，避免单个弱匹配的误推荐
        return if (best.value >= 2) best.key else null
    }
}
