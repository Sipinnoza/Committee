package com.znliang.committee.data.repository

import com.znliang.committee.data.db.DecisionActionDao
import com.znliang.committee.data.db.DecisionActionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionRepository @Inject constructor(
    private val dao: DecisionActionDao,
) {
    suspend fun insert(action: DecisionActionEntity): Long = dao.insert(action)
    suspend fun insertAll(actions: List<DecisionActionEntity>) = dao.insertAll(actions)
    fun observePending(): Flow<List<DecisionActionEntity>> = dao.observePending()
    fun observeAll(): Flow<List<DecisionActionEntity>> = dao.observeAll()
    suspend fun updateStatus(id: Long, status: String) = dao.updateStatus(id, status)
    suspend fun delete(id: Long) = dao.delete(id)
}
