package com.znliang.committee.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.data.db.SkillDefinitionDao
import com.znliang.committee.data.db.SkillDefinitionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SkillManagementViewModel @Inject constructor(
    private val skillDao: SkillDefinitionDao,
) : ViewModel() {

    val skills: StateFlow<List<SkillDefinitionEntity>> = skillDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSkill(
        name: String,
        description: String,
        parameters: String,
        executionType: String,
        executionConfig: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            skillDao.upsert(
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
            skillDao.upsert(
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
            skillDao.deleteById(id)
        }
    }

    fun toggleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            skillDao.setEnabled(id, enabled)
        }
    }
}
