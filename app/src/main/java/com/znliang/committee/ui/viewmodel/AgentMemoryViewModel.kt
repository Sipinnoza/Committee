package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.data.db.*
import com.znliang.committee.domain.model.AgentRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentMemoryStats(
    val experienceCount: Int = 0,
    val skillCount: Int = 0,
    val changelogCount: Int = 0,
    val latestOutcome: MeetingOutcomeEntity? = null,
)

data class AgentMemoryDetail(
    val experiences: List<AgentEvolutionEntity> = emptyList(),
    val skills: List<AgentSkillEntity> = emptyList(),
    val changelogs: List<PromptChangelogEntity> = emptyList(),
    val outcomes: List<MeetingOutcomeEntity> = emptyList(),
)

@HiltViewModel
class AgentMemoryViewModel @Inject constructor(
    private val evolutionDao: AgentEvolutionDao,
    private val skillDao: AgentSkillDao,
    private val changelogDao: PromptChangelogDao,
    private val outcomeDao: MeetingOutcomeDao,
) : ViewModel() {

    private val _stats = MutableStateFlow<Map<String, AgentMemoryStats>>(emptyMap())
    val stats: StateFlow<Map<String, AgentMemoryStats>> = _stats.asStateFlow()

    private val _detail = MutableStateFlow<AgentMemoryDetail?>(null)
    val detail: StateFlow<AgentMemoryDetail?> = _detail.asStateFlow()

    init { loadAllStats() }

    fun loadAllStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val map = AgentRole.entries.associate { role ->
                val exps = evolutionDao.getByRole(role.id, 1)  // just count
                val expCount = evolutionDao.getByRole(role.id, 100).size
                val skills = skillDao.getByRole(role.id)
                val changes = changelogDao.getByRole(role.id)
                val outcomes = outcomeDao.getByRole(role.id, 1)
                role.id to AgentMemoryStats(
                    experienceCount = expCount,
                    skillCount = skills.size,
                    changelogCount = changes.size,
                    latestOutcome = outcomes.firstOrNull(),
                )
            }
            _stats.value = map
        }
    }

    fun loadDetail(role: AgentRole) {
        viewModelScope.launch(Dispatchers.IO) {
            _detail.value = AgentMemoryDetail(
                experiences = evolutionDao.getByRole(role.id, 50),
                skills = skillDao.getByRole(role.id),
                changelogs = changelogDao.getByRole(role.id),
                outcomes = outcomeDao.getByRole(role.id, 20),
            )
        }
    }

    fun clearDetail() { _detail.value = null }
}
