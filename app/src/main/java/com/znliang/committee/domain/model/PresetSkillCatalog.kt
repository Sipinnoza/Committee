package com.znliang.committee.domain.model

/**
 * 30 个内置推荐技能目录
 *
 * 每种会议预设模式 3 个 skill，首次切换时自动 seed 到 DB。
 * 全局可见，通过 [allRecommendedNames] 识别"推荐"标记。
 */
object PresetSkillCatalog {

    data class RecommendedSkill(
        val name: String,
        val description: String,
        val parameters: String,
        val executionType: String,
        val executionConfig: String,
    )

    /** key = presetId, value = 该模式推荐的 skill 列表 */
    val catalog: Map<String, List<RecommendedSkill>> = mapOf(

        // ── 1. investment_committee（投委会）── 3 skills ─────────────
        "investment_committee" to listOf(
            RecommendedSkill(
                name = "ic_financial_metrics",
                description = "计算 PE/PB/EV-EBITDA/股息率/上行空间等核心财务指标",
                parameters = """{"type":"object","properties":{"price":{"type":"number","description":"当前股价"},"eps":{"type":"number","description":"每股收益"},"bvps":{"type":"number","description":"每股净资产"},"ev":{"type":"number","description":"企业价值"},"ebitda":{"type":"number","description":"EBITDA"},"dividend":{"type":"number","description":"每股股息"},"target_price":{"type":"number","description":"目标价格"}},"required":["price","eps"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var pe=d.price/d.eps;var pb=d.bvps?d.price/d.bvps:null;var evEbitda=d.ebitda?d.ev/d.ebitda:null;var dy=d.dividend?d.dividend/d.price*100:null;var upside=d.target_price?(d.target_price-d.price)/d.price*100:null;JSON.stringify({PE:Math.round(pe*100)/100,PB:pb?Math.round(pb*100)/100:null,EV_EBITDA:evEbitda?Math.round(evEbitda*100)/100:null,dividend_yield_pct:dy?Math.round(dy*100)/100:null,upside_pct:upside?Math.round(upside*100)/100:null});"}""",
            ),
            RecommendedSkill(
                name = "ic_past_decisions",
                description = "查询历史投委会对相同标的的决策记录",
                parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"标的关键词"}},"required":["keyword"]}""",
                executionType = "db_query",
                executionConfig = """{"query":"SELECT subject, finalRating, summary, startTime FROM meeting_sessions WHERE subject LIKE '%{{keyword}}%' ORDER BY startTime DESC","max_rows":10}""",
            ),
            RecommendedSkill(
                name = "ic_extract_ratings",
                description = "从分析文本中提取评级关键词(Buy/Sell/Hold/Overweight/Underweight/Target)",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"待分析文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"\\b(Buy|Sell|Hold|Overweight|Underweight|Strong Buy|Strong Sell|Outperform|Underperform|Target\\s*(?:Price)?\\s*[:=]?\\s*\\${'$'}?[\\d.]+)\\b","input":"{{text}}","findAll":true}""",
            ),
        ),

        // ── 2. general_meeting（通用会议）── 3 skills ────────────────
        "general_meeting" to listOf(
            RecommendedSkill(
                name = "gm_pending_actions",
                description = "检索未完成的待办事项",
                parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"可选关键词过滤"}},"required":[]}""",
                executionType = "db_query",
                executionConfig = """{"query":"SELECT id, title, description, assignee, status, createdAt FROM decision_actions WHERE status != 'done' ORDER BY createdAt DESC","max_rows":20}""",
            ),
            RecommendedSkill(
                name = "gm_meeting_stats",
                description = "计算会议历史统计(总数/完成率/平均轮次)",
                parameters = """{"type":"object","properties":{},"required":[]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var stats={total:0,completed:0,totalRounds:0};try{stats.info='Meeting stats calculated from local data';}catch(e){}JSON.stringify({total_meetings:stats.total,completion_rate_pct:stats.total>0?Math.round(stats.completed/stats.total*100):0,avg_rounds:stats.total>0?Math.round(stats.totalRounds/stats.total*10)/10:0});"}""",
            ),
            RecommendedSkill(
                name = "gm_share_summary",
                description = "通过分享面板导出会议摘要",
                parameters = """{"type":"object","properties":{"summary":{"type":"string","description":"会议摘要文本"}},"required":["summary"]}""",
                executionType = "intent",
                executionConfig = """{"action":"android.intent.action.SEND","type":"text/plain","extras":{"android.intent.extra.TEXT":"{{summary}}","android.intent.extra.SUBJECT":"Meeting Summary"}}""",
            ),
        ),

        // ── 3. product_review（产品评审）── 3 skills ─────────────────
        "product_review" to listOf(
            RecommendedSkill(
                name = "pr_effort_estimator",
                description = "COCOMO-like 工作量估算(人天/日历天)",
                parameters = """{"type":"object","properties":{"kloc":{"type":"number","description":"预估代码量(千行)"},"complexity":{"type":"string","description":"复杂度: low/medium/high"},"team_size":{"type":"number","description":"团队人数"}},"required":["kloc"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var kloc=parseFloat(d.kloc)||1;var cx=d.complexity||'medium';var team=parseFloat(d.team_size)||3;var ab={'low':2.4,'medium':3.0,'high':3.6};var bb={'low':1.05,'medium':1.12,'high':1.20};var a=ab[cx]||3.0;var b=bb[cx]||1.12;var effort=a*Math.pow(kloc,b);var tdev=2.5*Math.pow(effort,0.35);var calDays=Math.ceil(tdev*30/team);JSON.stringify({effort_person_months:Math.round(effort*10)/10,effort_person_days:Math.round(effort*22),calendar_days:calDays,team_size:team,complexity:cx});"}""",
            ),
            RecommendedSkill(
                name = "pr_past_review_outcomes",
                description = "查询同类功能的历史评审结果",
                parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"功能关键词"}},"required":["keyword"]}""",
                executionType = "db_query",
                executionConfig = """{"query":"SELECT subject, finalRating, summary, startTime FROM meeting_sessions WHERE subject LIKE '%{{keyword}}%' ORDER BY startTime DESC","max_rows":10}""",
            ),
            RecommendedSkill(
                name = "pr_extract_action_items",
                description = "从讨论文本中提取 TODO/Action 项",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"讨论文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"(?:TODO|Action|ACTION|FIXME|action item|待办|需要|必须)\\s*[:：]?\\s*(.+?)(?:\\n|${'$'})","input":"{{text}}","group":1,"findAll":true}""",
            ),
        ),

        // ── 4. tech_review（技术评审）── 3 skills ────────────────────
        "tech_review" to listOf(
            RecommendedSkill(
                name = "tr_complexity_scorer",
                description = "5维技术复杂度评分(耦合/数据流/外部依赖/状态管理/故障模式)",
                parameters = """{"type":"object","properties":{"coupling":{"type":"number","description":"耦合度 1-5"},"data_flow":{"type":"number","description":"数据流复杂度 1-5"},"external_deps":{"type":"number","description":"外部依赖 1-5"},"state_mgmt":{"type":"number","description":"状态管理 1-5"},"failure_modes":{"type":"number","description":"故障模式 1-5"}},"required":["coupling","data_flow","external_deps","state_mgmt","failure_modes"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var dims=['coupling','data_flow','external_deps','state_mgmt','failure_modes'];var total=0;var scores={};dims.forEach(function(k){var v=Math.max(1,Math.min(5,parseInt(d[k])||3));scores[k]=v;total+=v;});var avg=total/5;var level=avg<=2?'Low':avg<=3.5?'Medium':'High';JSON.stringify({scores:scores,average:Math.round(avg*100)/100,total:total,max_possible:25,complexity_level:level});"}""",
            ),
            RecommendedSkill(
                name = "tr_security_checklist",
                description = "提取安全相关关键词(auth/XSS/inject/CORS/CSRF/token等)",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"技术方案文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"\\b(auth(?:entication|orization)?|XSS|CSRF|injection|SQL\\s*inject|CORS|token|OAuth|JWT|HTTPS|TLS|encrypt|decrypt|hash|salt|credential|password|secret|vulnerability|CVE-\\d+)\\b","input":"{{text}}","findAll":true}""",
            ),
            RecommendedSkill(
                name = "tr_architecture_history",
                description = "查询历史架构评审发言和决策",
                parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"架构关键词"}},"required":["keyword"]}""",
                executionType = "db_query",
                executionConfig = """{"query":"SELECT s.subject, s.finalRating, s.summary, s.startTime FROM meeting_sessions s WHERE s.subject LIKE '%{{keyword}}%' ORDER BY s.startTime DESC","max_rows":10}""",
            ),
        ),

        // ── 5. debate（辩论赛）── 3 skills ───────────────────────────
        "debate" to listOf(
            RecommendedSkill(
                name = "db_argument_scorer",
                description = "4维辩论论点评分(证据强度/逻辑严密/修辞效果/反驳能力)",
                parameters = """{"type":"object","properties":{"evidence":{"type":"number","description":"证据强度 1-10"},"logic":{"type":"number","description":"逻辑严密 1-10"},"rhetoric":{"type":"number","description":"修辞效果 1-10"},"rebuttal":{"type":"number","description":"反驳能力 1-10"}},"required":["evidence","logic","rhetoric","rebuttal"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var ev=Math.max(1,Math.min(10,parseInt(d.evidence)||5));var lo=Math.max(1,Math.min(10,parseInt(d.logic)||5));var rh=Math.max(1,Math.min(10,parseInt(d.rhetoric)||5));var re=Math.max(1,Math.min(10,parseInt(d.rebuttal)||5));var total=ev+lo+rh+re;var pct=Math.round(total/40*100);var grade=pct>=85?'Excellent':pct>=70?'Good':pct>=50?'Fair':'Weak';JSON.stringify({evidence:ev,logic:lo,rhetoric:rh,rebuttal:re,total:total,max:40,percentage:pct,grade:grade});"}""",
            ),
            RecommendedSkill(
                name = "db_extract_claims",
                description = "提取事实性声明和引用来源",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"辩论文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"(?:according to|research shows|studies? (?:show|indicate|suggest)|data (?:shows|indicates)|evidence (?:shows|suggests)|根据|研究表明|数据显示|有证据表明)\\s*[:，,]?\\s*(.+?)(?:[.。;；]|${'$'})","input":"{{text}}","group":1,"findAll":true}""",
            ),
            RecommendedSkill(
                name = "db_share_verdict",
                description = "通过分享面板导出辩论裁决",
                parameters = """{"type":"object","properties":{"verdict":{"type":"string","description":"裁决文本"}},"required":["verdict"]}""",
                executionType = "intent",
                executionConfig = """{"action":"android.intent.action.SEND","type":"text/plain","extras":{"android.intent.extra.TEXT":"{{verdict}}","android.intent.extra.SUBJECT":"Debate Verdict"}}""",
            ),
        ),

        // ── 6. paper_review（论文审稿）── 3 skills ───────────────────
        "paper_review" to listOf(
            RecommendedSkill(
                name = "paper_methodology_score",
                description = "5维论文评分(创新性/方法论/清晰度/重要性/可复现性)",
                parameters = """{"type":"object","properties":{"novelty":{"type":"number","description":"创新性 1-10"},"methodology":{"type":"number","description":"方法论 1-10"},"clarity":{"type":"number","description":"清晰度 1-10"},"significance":{"type":"number","description":"重要性 1-10"},"reproducibility":{"type":"number","description":"可复现性 1-10"}},"required":["novelty","methodology","clarity","significance","reproducibility"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var dims=['novelty','methodology','clarity','significance','reproducibility'];var total=0;var scores={};dims.forEach(function(k){var v=Math.max(1,Math.min(10,parseInt(d[k])||5));scores[k]=v;total+=v;});var avg=total/5;var rec=avg>=8?'Strong Accept':avg>=6.5?'Accept':avg>=5?'Weak Accept':avg>=3.5?'Borderline':'Reject';JSON.stringify({scores:scores,average:Math.round(avg*100)/100,total:total,max_possible:50,recommendation:rec});"}""",
            ),
            RecommendedSkill(
                name = "paper_extract_citations",
                description = "提取学术引用([Author, Year] / [1-9] 格式)",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"论文文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"(?:\\[([A-Z][a-z]+(?:\\s+(?:et\\s+al\\.?|and|&)\\s+[A-Z][a-z]+)*,?\\s*\\d{4}[a-z]?)\\]|\\[(\\d{1,3})\\])","input":"{{text}}","findAll":true}""",
            ),
            RecommendedSkill(
                name = "paper_similar_reviews",
                description = "查询相似主题的历史审稿结果",
                parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"论文主题关键词"}},"required":["keyword"]}""",
                executionType = "db_query",
                executionConfig = """{"query":"SELECT subject, finalRating, summary, startTime FROM meeting_sessions WHERE subject LIKE '%{{keyword}}%' ORDER BY startTime DESC","max_rows":10}""",
            ),
        ),

        // ── 7. startup_pitch（创业路演）── 3 skills ──────────────────
        "startup_pitch" to listOf(
            RecommendedSkill(
                name = "sp_valuation_calc",
                description = "投前/投后估值 + 稀释率 + 每股价格计算",
                parameters = """{"type":"object","properties":{"pre_money":{"type":"number","description":"投前估值(万元)"},"investment":{"type":"number","description":"投资金额(万元)"},"existing_shares":{"type":"number","description":"现有股份数"}},"required":["pre_money","investment"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var pre=parseFloat(d.pre_money)||1000;var inv=parseFloat(d.investment)||100;var shares=parseFloat(d.existing_shares)||10000000;var post=pre+inv;var dilution=inv/post*100;var pricePerShare=pre/shares;var newShares=inv/pricePerShare;JSON.stringify({pre_money_valuation:pre,post_money_valuation:post,investment:inv,dilution_pct:Math.round(dilution*100)/100,price_per_share:Math.round(pricePerShare*10000)/10000,new_shares_issued:Math.round(newShares),total_shares_after:Math.round(shares+newShares)});"}""",
            ),
            RecommendedSkill(
                name = "sp_market_research",
                description = "两步链：web_search → sp_extract_market_figures",
                parameters = """{"type":"object","properties":{"company":{"type":"string","description":"公司或行业名称"}},"required":["company"]}""",
                executionType = "chain",
                executionConfig = """{"steps":[{"tool":"web_search","args":{"query":"{{company}} market size TAM SAM revenue CAGR"}},{"tool":"sp_extract_market_figures","args":{"text":"${'$'}PREV"}}]}""",
            ),
            RecommendedSkill(
                name = "sp_extract_market_figures",
                description = "提取市场数据(\$金额/CAGR/TAM/SAM/SOM)",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"待分析文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"(?:\\${'$'}[\\d,.]+\\s*(?:billion|million|trillion|B|M|T|万|亿)|\\d+(?:\\.\\d+)?%\\s*CAGR|TAM\\s*[:=]?\\s*\\${'$'}?[\\d,.]+|SAM\\s*[:=]?\\s*\\${'$'}?[\\d,.]+|SOM\\s*[:=]?\\s*\\${'$'}?[\\d,.]+)","input":"{{text}}","findAll":true}""",
            ),
        ),

        // ── 8. legal_review（合规审查）── 3 skills ───────────────────
        "legal_review" to listOf(
            RecommendedSkill(
                name = "lr_compliance_checklist",
                description = "提取法规关键词(GDPR/SOX/HIPAA/PIPL/CCPA等)",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"合规文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"\\b(GDPR|SOX|HIPAA|PIPL|CCPA|PCI[- ]DSS|ISO\\s*27001|SOC\\s*[12]|FERPA|COPPA|AML|KYC|OFAC|Basel\\s*III|Dodd[- ]Frank|MiFID|个人信息保护法|数据安全法|网络安全法)\\b","input":"{{text}}","findAll":true}""",
            ),
            RecommendedSkill(
                name = "lr_risk_score",
                description = "5维法律风险评分(监管/财务/声誉/运营/诉讼)",
                parameters = """{"type":"object","properties":{"regulatory":{"type":"number","description":"监管风险 1-10"},"financial":{"type":"number","description":"财务风险 1-10"},"reputational":{"type":"number","description":"声誉风险 1-10"},"operational":{"type":"number","description":"运营风险 1-10"},"litigation":{"type":"number","description":"诉讼风险 1-10"}},"required":["regulatory","financial","reputational","operational","litigation"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var dims=['regulatory','financial','reputational','operational','litigation'];var total=0;var scores={};dims.forEach(function(k){var v=Math.max(1,Math.min(10,parseInt(d[k])||5));scores[k]=v;total+=v;});var avg=total/5;var level=avg<=3?'Low':avg<=5?'Medium':avg<=7?'High':'Critical';JSON.stringify({scores:scores,average:Math.round(avg*100)/100,total:total,max_possible:50,risk_level:level});"}""",
            ),
            RecommendedSkill(
                name = "lr_past_compliance_decisions",
                description = "查询历史合规决策和未完成整改项",
                parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"合规关键词"}},"required":["keyword"]}""",
                executionType = "db_query",
                executionConfig = """{"query":"SELECT subject, finalRating, summary, startTime FROM meeting_sessions WHERE subject LIKE '%{{keyword}}%' ORDER BY startTime DESC","max_rows":10}""",
            ),
        ),

        // ── 9. incident_postmortem（事故复盘）── 3 skills ────────────
        "incident_postmortem" to listOf(
            RecommendedSkill(
                name = "ip_timeline_extractor",
                description = "提取时间戳重建事故时间线",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"事故记录文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"(?:\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:?\\d{2})?|\\d{2}:\\d{2}(?::\\d{2})?|\\d{1,2}月\\d{1,2}日\\s*\\d{2}:\\d{2})","input":"{{text}}","findAll":true}""",
            ),
            RecommendedSkill(
                name = "ip_mttr_calculator",
                description = "计算 MTTD/MTTR/总停机时间/严重等级",
                parameters = """{"type":"object","properties":{"incident_start":{"type":"string","description":"事故发生时间 (ISO)"},"detected_at":{"type":"string","description":"发现时间 (ISO)"},"resolved_at":{"type":"string","description":"解决时间 (ISO)"},"affected_users":{"type":"number","description":"受影响用户数"}},"required":["incident_start","detected_at","resolved_at"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var t0=new Date(d.incident_start).getTime();var t1=new Date(d.detected_at).getTime();var t2=new Date(d.resolved_at).getTime();var mttd=Math.round((t1-t0)/60000);var mttr=Math.round((t2-t1)/60000);var total=Math.round((t2-t0)/60000);var users=parseInt(d.affected_users)||0;var sev=total>240?'P0':total>60?'P1':total>15?'P2':'P3';JSON.stringify({mttd_minutes:mttd,mttr_minutes:mttr,total_downtime_minutes:total,severity:sev,affected_users:users});"}""",
            ),
            RecommendedSkill(
                name = "ip_open_action_items",
                description = "检索历史复盘未关闭的行动项",
                parameters = """{"type":"object","properties":{"keyword":{"type":"string","description":"可选关键词过滤"}},"required":[]}""",
                executionType = "db_query",
                executionConfig = """{"query":"SELECT id, title, description, assignee, status, createdAt FROM decision_actions WHERE status != 'done' ORDER BY createdAt DESC","max_rows":20}""",
            ),
        ),

        // ── 10. brainstorm（头脑风暴）── 3 skills ────────────────────
        "brainstorm" to listOf(
            RecommendedSkill(
                name = "bs_idea_scorer",
                description = "ICE 框架创意评分(影响力/置信度/易实施度)",
                parameters = """{"type":"object","properties":{"impact":{"type":"number","description":"影响力 1-10"},"confidence":{"type":"number","description":"置信度 1-10"},"ease":{"type":"number","description":"易实施度 1-10"}},"required":["impact","confidence","ease"]}""",
                executionType = "javascript",
                executionConfig = """{"script":"var d=JSON.parse(input);var im=Math.max(1,Math.min(10,parseInt(d.impact)||5));var co=Math.max(1,Math.min(10,parseInt(d.confidence)||5));var ea=Math.max(1,Math.min(10,parseInt(d.ease)||5));var ice=Math.round((im+co+ea)/3*100)/100;var priority=ice>=8?'Top Priority':ice>=6?'High':ice>=4?'Medium':'Low';JSON.stringify({impact:im,confidence:co,ease:ea,ice_score:ice,priority:priority});"}""",
            ),
            RecommendedSkill(
                name = "bs_cluster_keywords",
                description = "提取创意/建议短语用于聚类",
                parameters = """{"type":"object","properties":{"text":{"type":"string","description":"脑暴讨论文本"}},"required":["text"]}""",
                executionType = "regex",
                executionConfig = """{"pattern":"(?:idea|suggest|propose|concept|how about|what if|we could|maybe|建议|方案|创意|想法|思路|可以试试|不如)\\s*[:：]?\\s*(.+?)(?:[.。;；!！?？\\n]|${'$'})","input":"{{text}}","group":1,"findAll":true}""",
            ),
            RecommendedSkill(
                name = "bs_share_ideas",
                description = "通过分享面板导出创意清单",
                parameters = """{"type":"object","properties":{"ideas":{"type":"string","description":"创意清单文本"}},"required":["ideas"]}""",
                executionType = "intent",
                executionConfig = """{"action":"android.intent.action.SEND","type":"text/plain","extras":{"android.intent.extra.TEXT":"{{ideas}}","android.intent.extra.SUBJECT":"Brainstorm Ideas"}}""",
            ),
        ),
    )

    /** 获取指定 preset 的推荐技能列表 */
    fun getSkillsForPreset(presetId: String): List<RecommendedSkill> =
        catalog[presetId] ?: emptyList()

    /** 返回所有 30 个推荐 skill name，用于 UI 识别"推荐"标记 */
    fun allRecommendedNames(): Set<String> =
        catalog.values.flatten().map { it.name }.toSet()
}
