package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.data.db.*
import com.znliang.committee.domain.model.MeetingPresetConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentMemoryStats(
    val experienceCount: Int = 0,               // 经验记忆条数
    val skillCount: Int = 0,                    // 已验证技能数
    val changelogCount: Int = 0,                // Prompt变更次数
    val latestOutcome: MeetingOutcomeEntity? = null, // 最近一次会议结果
    val voteAccuracy: Float? = null,            // 投票准确率（>=3次投票后计算）
    val meetingCount: Int = 0,                  // 参与会议总数
)

data class AgentMemoryDetail(
    val experiences: List<AgentEvolutionEntity> = emptyList(),   // 经验记忆列表
    val skills: List<AgentSkillEntity> = emptyList(),            // 技能库列表
    val changelogs: List<PromptChangelogEntity> = emptyList(),   // Prompt变更历史
    val outcomes: List<MeetingOutcomeEntity> = emptyList(),      // 会议结果列表
)

@HiltViewModel
class AgentMemoryViewModel @Inject constructor(
    private val evolutionDao: AgentEvolutionDao,
    private val skillDao: AgentSkillDao,
    private val changelogDao: PromptChangelogDao,
    private val outcomeDao: MeetingOutcomeDao,
    private val presetConfig: MeetingPresetConfig,
) : ViewModel() {

    private val _stats = MutableStateFlow<Map<String, AgentMemoryStats>>(emptyMap())
    val stats: StateFlow<Map<String, AgentMemoryStats>> = _stats.asStateFlow()

    private val _detail = MutableStateFlow<AgentMemoryDetail?>(null)
    val detail: StateFlow<AgentMemoryDetail?> = _detail.asStateFlow()

    init { loadAllStats() }

    fun loadAllStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val roles = presetConfig.activeRoles()
            val since = 0L // all time
            val map = roles.associate { role ->
                val expCount = evolutionDao.getByRole(role.id, 100).size
                val skills = skillDao.getByRole(role.id)
                val changes = changelogDao.getByRole(role.id)
                val outcomes = outcomeDao.getByRole(role.id, 1)
                val totalVotes = outcomeDao.totalVotesSince(role.id, since)
                val correctVotes = outcomeDao.correctVotesSince(role.id, since)
                val accuracy = if (totalVotes >= 3) correctVotes.toFloat() / totalVotes else null
                role.id to AgentMemoryStats(
                    experienceCount = expCount,
                    skillCount = skills.size,
                    changelogCount = changes.size,
                    latestOutcome = outcomes.firstOrNull(),
                    voteAccuracy = accuracy,
                    meetingCount = totalVotes,
                )
            }
            _stats.value = map
        }
    }

    fun loadDetail(roleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _detail.value = AgentMemoryDetail(
                experiences = evolutionDao.getByRole(roleId, 50),
                skills = skillDao.getByRole(roleId),
                changelogs = changelogDao.getByRole(roleId),
                outcomes = outcomeDao.getByRole(roleId, 20),
            )
        }
    }

    fun clearDetail() { _detail.value = null }
}
