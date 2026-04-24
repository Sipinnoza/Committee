package com.znliang.committee.data.repository

import com.znliang.committee.data.db.SkillDefinitionDao
import com.znliang.committee.data.db.SkillDefinitionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillRepository @Inject constructor(
    private val dao: SkillDefinitionDao,
) {
    fun getAllEnabled(): Flow<List<SkillDefinitionEntity>> = dao.getAllEnabled()
    fun getAll(): Flow<List<SkillDefinitionEntity>> = dao.getAll()
    suspend fun getByName(name: String): SkillDefinitionEntity? = dao.getByName(name)
    suspend fun upsert(entity: SkillDefinitionEntity): Long = dao.upsert(entity)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
}
