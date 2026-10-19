package com.astrmobile.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val aptMirror: String = DEFAULT_APT_MIRROR,
    val pipIndex: String = DEFAULT_PIP_INDEX,
    val githubProxy: String = DEFAULT_GITHUB_PROXY,
    val autoStartOnBoot: Boolean = true,
    val keepAwake: Boolean = true,
    val napcatEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_APT_MIRROR = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
        const val DEFAULT_PIP_INDEX = "https://pypi.tuna.tsinghua.edu.cn/simple"
        const val DEFAULT_GITHUB_PROXY = "https://ghfast.top/"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "astrmobile_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val aptMirror = stringPreferencesKey("apt_mirror")
        val pipIndex = stringPreferencesKey("pip_index")
        val githubProxy = stringPreferencesKey("github_proxy")
        val autoStart = booleanPreferencesKey("auto_start")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val napcatEnabled = booleanPreferencesKey("napcat_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            aptMirror = prefs[Keys.aptMirror] ?: AppSettings.DEFAULT_APT_MIRROR,
            pipIndex = prefs[Keys.pipIndex] ?: AppSettings.DEFAULT_PIP_INDEX,
            githubProxy = prefs[Keys.githubProxy] ?: AppSettings.DEFAULT_GITHUB_PROXY,
            autoStartOnBoot = prefs[Keys.autoStart] ?: true,
            keepAwake = prefs[Keys.keepAwake] ?: true,
            napcatEnabled = prefs[Keys.napcatEnabled] ?: true,
        )
    }

    suspend fun setAptMirror(value: String) = context.settingsStore.edit { it[Keys.aptMirror] = value }

    suspend fun setPipIndex(value: String) = context.settingsStore.edit { it[Keys.pipIndex] = value }

    suspend fun setGithubProxy(value: String) = context.settingsStore.edit { it[Keys.githubProxy] = value }

    suspend fun setAutoStart(value: Boolean) = context.settingsStore.edit { it[Keys.autoStart] = value }

    suspend fun setKeepAwake(value: Boolean) = context.settingsStore.edit { it[Keys.keepAwake] = value }

    suspend fun setNapcatEnabled(value: Boolean) = context.settingsStore.edit { it[Keys.napcatEnabled] = value }
}
