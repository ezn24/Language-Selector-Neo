package vegabobo.languageselector.ui.screen.main

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import vegabobo.languageselector.BuildConfig
import vegabobo.languageselector.RootReceivedListener
import vegabobo.languageselector.dao.AppInfoDb
import vegabobo.languageselector.service.UserServiceProvider
import javax.inject.Inject


@HiltViewModel
class MainScreenVm @Inject constructor(
    val app: Application,
    appInfoDb: AppInfoDb,
    private val sharedPreferences: SharedPreferences
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MainScreenState(
            isShowSystemAppsHome = sharedPreferences.getBoolean(PREF_SHOW_SYSTEM_APPS_HOME, false)
        )
    )
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()
    var lastSelectedApp: AppInfo? = null
    val dao = appInfoDb.appInfoDao()
    private var localeScanJob: Job? = null

    fun getIndexFromAppInfoItem(): Int {
        return _uiState.value.listOfApps.indexOfFirst { it.pkg == lastSelectedApp?.pkg }
    }

    fun loadOperationMode() {
        if (Shell.getShell().isAlive)
            Shell.getShell().close()
        Shell.getShell()
        if (Shell.isAppGrantedRoot() == true) {
            _uiState.update { it.copy(operationMode = OperationMode.ROOT) }
            RootReceivedListener.onRootReceived()
            return
        }

        val isAvail = Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        if (isAvail) {
            _uiState.update { it.copy(operationMode = OperationMode.SHIZUKU) }
            return
        }

        _uiState.update { it.copy(operationMode = OperationMode.NONE) }
    }

    init {
        fillListOfApps()
    }

    fun parseBasicAppInfo(a: ApplicationInfo): AppInfo {
        val isSystemApp = (a.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val labels = arrayListOf<AppLabels>()
        if (isSystemApp)
            labels.add(AppLabels.SYSTEM_APP)
        return AppInfo(
            icon = app.packageManager.getAppIcon(a),
            name = app.packageManager.getLabel(a),
            pkg = a.packageName,
            labels = labels
        )
    }

    fun parseAppInfo(a: ApplicationInfo): AppInfo {
        val basicAppInfo = parseBasicAppInfo(a)
        val service = UserServiceProvider.getService()
        val languagePreferences = service.getApplicationLocales(a.packageName)
        val labels = basicAppInfo.labels.toMutableList()
        if (!languagePreferences.isEmpty)
            labels.add(AppLabels.MODIFIED)
        return basicAppInfo.copy(labels = labels)
    }

    fun fillListOfApps() {
        localeScanJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.operationMode == OperationMode.NONE)
                loadOperationMode()
            val packages = getInstalledPackages()
            val basicList = packages.map { parseBasicAppInfo(it) }
                .sortedBy { it.name.lowercase() }
            replaceAppLists(basicList)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false) }
            }
            scanLanguageStates(packages)
        }
    }

    private fun scanLanguageStates(packages: List<ApplicationInfo>) {
        localeScanJob = viewModelScope.launch(Dispatchers.IO) {
            val service = runCatching { UserServiceProvider.getService() }.getOrNull() ?: return@launch
            val modifiedPackages = mutableSetOf<String>()
            var scannedCount = 0

            for (packageInfo in packages) {
                val languagePreferences = runCatching {
                    service.getApplicationLocales(packageInfo.packageName)
                }.getOrNull()

                if (languagePreferences?.isEmpty == false) {
                    modifiedPackages.add(packageInfo.packageName)
                }

                scannedCount++
                if (scannedCount % LOCALE_SCAN_BATCH_SIZE == 0) {
                    applyLanguageScanResult(modifiedPackages, resort = false)
                }
            }

            applyLanguageScanResult(modifiedPackages, resort = true)
        }
    }

    private suspend fun applyLanguageScanResult(modifiedPackages: Set<String>, resort: Boolean) {
        withContext(Dispatchers.Main) {
            val updatedList = _uiState.value.listOfApps.map { appInfo ->
                val labels = appInfo.labels.toMutableList()
                val isModified = appInfo.pkg in modifiedPackages
                if (isModified && !labels.contains(AppLabels.MODIFIED)) {
                    labels.add(AppLabels.MODIFIED)
                } else if (!isModified) {
                    labels.remove(AppLabels.MODIFIED)
                }
                appInfo.copy(labels = labels)
            }.let { list ->
                if (resort) {
                    list.sortedBy { it.name.lowercase() }.sortedBy { !it.isModified() }
                } else {
                    list
                }
            }
            replaceAppLists(updatedList)
        }
    }

    private suspend fun replaceAppLists(apps: List<AppInfo>) {
        withContext(Dispatchers.Main) {
            _uiState.value.listOfApps.clear()
            _uiState.value.listOfApps.addAll(apps)
            if (_uiState.value.searchTextFieldValue.isBlank()) {
                _uiState.value.searchResults.clear()
                _uiState.value.searchResults.addAll(apps)
            } else {
                launchSearch(_uiState.value.searchTextFieldValue, debounce = false)
            }
        }
    }

    fun getInstalledPackages(): List<ApplicationInfo> {
        return app.packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(0)
        ).mapNotNull {
            if (!it.enabled || BuildConfig.APPLICATION_ID == it.packageName)
                null
            else
                it
        }
    }

    fun toggleDropdown() {
        val newDropdownVisibility = !uiState.value.isDropdownVisible
        _uiState.update { it.copy(isDropdownVisible = newDropdownVisibility) }
    }

    fun toggleSystemAppsVisibility() {
        val newShowSystemApps = !uiState.value.isShowSystemAppsHome
        sharedPreferences.edit()
            .putBoolean(PREF_SHOW_SYSTEM_APPS_HOME, newShowSystemApps)
            .apply()
        _uiState.update {
            it.copy(
                isLoading = true,
                isShowSystemAppsHome = newShowSystemApps
            )
        }
        fillListOfApps()
        toggleDropdown()
    }

    fun onClickProceedShizuku() {
        loadOperationMode()
    }

    private var searchJob: Job? = null

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val LOCALE_SCAN_BATCH_SIZE = 20
        private const val PREF_SHOW_SYSTEM_APPS_HOME = "show_system_apps_home"
    }

    fun onSearchTextFieldChange(newText: String) {
        val normalized = newText.replace(Regex("[\r\n]"), "")
        val triggeredByImeSearch = newText.any { it == '\n' || it == '\r' }
        val previousValue = _uiState.value.searchTextFieldValue
        if (!triggeredByImeSearch && previousValue == normalized) {
            return
        }

        _uiState.update { it.copy(searchTextFieldValue = normalized) }
        launchSearch(normalized, debounce = !triggeredByImeSearch)
    }

    fun onSearchConfirmed(query: String) {
        val normalized = query.replace(Regex("[\r\n]"), "")
        _uiState.update { it.copy(searchTextFieldValue = normalized) }
        launchSearch(normalized, debounce = false)
    }

    private fun launchSearch(query: String, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) {
                delay(SEARCH_DEBOUNCE_MS)
            }

            val appsSnapshot = _uiState.value.listOfApps.toList()
            val selectedLabels = _uiState.value.selectLabels.toList()
            val requireModified = selectedLabels.contains(AppLabels.MODIFIED)
            val showSystemApps = selectedLabels.contains(AppLabels.SYSTEM_APP)
            val normalizedQuery = query.trim().lowercase()

            val results = withContext(Dispatchers.Default) {
                val queryFiltered = if (normalizedQuery.isEmpty()) {
                    appsSnapshot
                } else {
                    appsSnapshot.filter {
                        it.pkg.lowercase().contains(normalizedQuery) ||
                                it.name.lowercase().contains(normalizedQuery)
                    }
                }

                queryFiltered.filter { app ->
                    if (requireModified && !app.isModified()) {
                        return@filter false
                    }

                    if (!showSystemApps && app.isSystemApp()) {
                        return@filter false
                    }

                    true
                }
            }

            val searchResults = _uiState.value.searchResults
            searchResults.clear()
            searchResults.addAll(results)
        }
    }

    fun onSearchExpandedChange() {
        val isExpanded = !uiState.value.isExpanded
        _uiState.update { it.copy(isExpanded = isExpanded) }
        if (isExpanded)
            updateHistory()
        else {
            _uiState.update { it.copy(searchTextFieldValue = "") }
            launchSearch("", debounce = false)
        }
    }

    fun onSelectedLabelChange(label: AppLabels) {
        val lb = _uiState.value.selectLabels
        if (lb.contains(label))
            lb.remove(label)
        else
            lb.add(label)
        launchSearch(_uiState.value.searchTextFieldValue, debounce = false)
    }

    fun updateHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val appInfoList = dao.getHistory().map { it.pkg }
            val history = appInfoList.mapNotNull { pkg ->
                val listOfApps = _uiState.value.listOfApps
                val idx = listOfApps.indexOfFirst { it.pkg == pkg }
                if (idx == -1)
                    null
                else
                    listOfApps[idx]
            }
            _uiState.value.history.clear()
            _uiState.value.history.addAll(history)
        }
    }

    fun addAppToHistory(ai: AppInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.findByPkg(ai.pkg) == null) {
                dao.insert(ai.toAppInfoEntity())
            }
            dao.setLastSelected(ai.pkg, System.currentTimeMillis())
            updateHistory()
        }
    }

    fun onClickClear() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.cleanLastSelectedAll()
            updateHistory()
        }
    }

    fun reloadLastSelectedItem() {
        if (lastSelectedApp == null) return
        val pkg = app.packageManager.getApplicationInfo(lastSelectedApp!!.pkg, 0)
        val updatedAi = parseAppInfo(pkg)
        val apps = _uiState.value.listOfApps
        val idx = apps.indexOfFirst { it.pkg == updatedAi.pkg }
        if (idx != -1 && updatedAi.labels != apps[idx].labels) {
            apps[idx] = updatedAi
            val newList = _uiState.value.listOfApps.sortedBy { it.name.lowercase() }
                .sortedBy { !it.isModified() }.toMutableList()
            _uiState.update {
                it.copy(
                    listOfApps = newList,
                    snackBarDisplay = if (updatedAi.isModified()) SnackBarDisplay.MOVED_TO_TOP else SnackBarDisplay.MOVED_TO_BOTTOM
                )
            }
            return
        }
    }

    fun resetSnackBarDisplay() = _uiState.update { it.copy(snackBarDisplay = SnackBarDisplay.NONE) }

    fun onClickApp(ai: AppInfo) {
        lastSelectedApp = ai
        addAppToHistory(ai)
    }
}
