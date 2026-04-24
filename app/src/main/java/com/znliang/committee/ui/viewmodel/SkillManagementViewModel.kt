package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.data.db.SkillDefinitionEntity
import com.znliang.committee.data.repository.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SkillManagementViewModel @Inject constructor(
    private val skillRepo: SkillRepository,
) : ViewModel() {

    val skills: StateFlow<List<SkillDefinitionEntity>> = skillRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSkill(
        name: String,
        description: String,
        parameters: String,
        executionType: String,
        executionConfig: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRepo.upsert(
                SkillDefinitionEntity(
                    name = name,
                    description = description,
                    parameters = parameters,
                    executionType = executionType,
                    executionConfig = executionConfig,
                    enabled = true,
                )
            )
        }
    }

    fun updateSkill(
        existing: SkillDefinitionEntity,
        name: String,
        description: String,
        parameters: String,
        executionType: String,
        executionConfig: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRepo.upsert(
                existing.copy(
                    name = name,
                    description = description,
                    parameters = parameters,
                    executionType = executionType,
                    executionConfig = executionConfig,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteSkill(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRepo.deleteById(id)
        }
    }

    fun toggleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRepo.setEnabled(id, enabled)
        }
    }
}
