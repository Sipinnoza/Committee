package com.znliang.committee.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.znliang.committee.data.repository.AppConfigRepository
import com.znliang.committee.domain.model.AppConfig
import com.znliang.committee.domain.model.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @description:
 * @author xiebin04
 * @date 2026/04/19
 * @version
 */

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appConfigRepository: AppConfigRepository
) : ViewModel() {

    var appConfig by mutableStateOf(AppConfig())
        private set

    init {
        viewModelScope.launch {
            appConfig = appConfigRepository.getConfig()
        }
    }

    fun saveLanguage(language: AppLanguage) {
        viewModelScope.launch {
            appConfigRepository.saveLanguage(language)
            appConfig = appConfig.copy(selectedLanguage = language)
        }
    }
}