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
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import vegabobo.languageselector.BuildConfig
import vegabobo.languageselector.RootReceivedListener
import vegabobo.languageselector.dao.AppInfoDb
import vegabobo.languageselector.service.UserServiceProvider
import java.io.File
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
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            _uiState.update {
                it.copy(
                    operationMode = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        OperationMode.SHIZUKU
                    } else {
                        OperationMode.NONE
                    }
                )
            }
        }

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
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        fillListOfApps()
    }

    override fun onCleared() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        super.onCleared()
    }

    fun parseBasicAppInfo(a: ApplicationInfo): AppInfo {
        val isSystemApp = (a.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val labels = arrayListOf<AppLabels>()
        if (isSystemApp)
            labels.add(AppLabels.SYSTEM_APP)
        return AppInfo(
            name = app.packageManager.getLabel(a),
            pkg = a.packageName,
            labels = labels,
            iconVersion = runCatching { File(a.sourceDir).lastModified() }.getOrDefault(0L),
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
            val cachedApps = loadCachedAppList()
            val showedCachedApps = displayCachedAppList(cachedApps)
            val cachedModifiedPackages = cachedApps
                .filter { it.isModified() }
                .mapTo(mutableSetOf()) { it.pkg }
            if (_uiState.value.operationMode == OperationMode.CHECKING)
                loadOperationMode()
            val packages = getInstalledPackages()
            val basicList = packages.map { packageInfo ->
                parseBasicAppInfo(packageInfo).let { appInfo ->
                    if (appInfo.pkg in cachedModifiedPackages) {
                        appInfo.copy(labels = appInfo.labels + AppLabels.MODIFIED)
                    } else {
                        appInfo
                    }
                }
            }.sortedWith(
                compareByDescending<AppInfo> { it.isModified() }
                    .thenBy { it.name.lowercase() }
            )
            replaceAppLists(basicList)
            saveCachedAppList(basicList)
            if (!showedCachedApps) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
            scanLanguageStates(packages)
        }
    }

    private fun scanLanguageStates(packages: List<ApplicationInfo>) {
        localeScanJob = viewModelScope.launch(Dispatchers.IO) {
            val service = runCatching { UserServiceProvider.getService() }.getOrNull() ?: return@launch
            val modifiedPackages = mutableSetOf<String>()
            val pendingModifiedPackages = mutableSetOf<String>()
            var scannedCount = 0
            var lastUiUpdateAt = 0L

            for (packageInfo in packages) {
                val languagePreferences = runCatching {
                    service.getApplicationLocales(packageInfo.packageName)
                }.getOrNull()

                if (languagePreferences?.isEmpty == false && modifiedPackages.add(packageInfo.packageName)) {
                    pendingModifiedPackages.add(packageInfo.packageName)
                }

                scannedCount++
                val now = System.currentTimeMillis()
                if (pendingModifiedPackages.isNotEmpty() &&
                    scannedCount % LOCALE_SCAN_BATCH_SIZE == 0 &&
                    now - lastUiUpdateAt >= LOCALE_SCAN_UPDATE_MIN_INTERVAL_MS
                ) {
                    applyLanguageScanDelta(pendingModifiedPackages.toSet())
                    pendingModifiedPackages.clear()
                    lastUiUpdateAt = now
                }

                if (scannedCount % LOCALE_SCAN_COOPERATIVE_YIELD_SIZE == 0) {
                    delay(1)
                }
            }

            if (pendingModifiedPackages.isNotEmpty()) {
                applyLanguageScanDelta(pendingModifiedPackages)
            }
            applyLanguageScanFinalSort(modifiedPackages)
        }
    }

    private suspend fun applyLanguageScanDelta(newModifiedPackages: Set<String>) {
        withContext(Dispatchers.Main) {
            updateModifiedLabelsInPlace(_uiState.value.listOfApps, newModifiedPackages)
            updateModifiedLabelsInPlace(_uiState.value.searchResults, newModifiedPackages)
        }
    }

    private suspend fun applyLanguageScanFinalSort(modifiedPackages: Set<String>) {
        val appsSnapshot = withContext(Dispatchers.Main) {
            _uiState.value.listOfApps.toList()
        }
        val sortedApps = withContext(Dispatchers.Default) {
            appsSnapshot.map { appInfo ->
                val labels = appInfo.labels.toMutableList()
                val isModified = appInfo.pkg in modifiedPackages
                if (isModified && !labels.contains(AppLabels.MODIFIED)) {
                    labels.add(AppLabels.MODIFIED)
                } else if (!isModified) {
                    labels.remove(AppLabels.MODIFIED)
                }
                appInfo.copy(labels = labels)
            }.sortedWith(
                compareByDescending<AppInfo> { it.isModified() }
                    .thenBy { it.name.lowercase() }
            )
        }
        replaceAppLists(sortedApps)
        saveCachedAppList(sortedApps)
    }

    private suspend fun displayCachedAppList(cachedApps: List<AppInfo>): Boolean {
        if (cachedApps.isEmpty()) {
            return false
        }
        replaceAppLists(cachedApps)
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(isLoading = false) }
        }
        return true
    }

    private fun loadCachedAppList(): List<AppInfo> {
        val raw = sharedPreferences.getString(PREF_APP_LIST_CACHE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val apps = ArrayList<AppInfo>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val pkg = item.optString("pkg")
                if (pkg.isBlank() || pkg == BuildConfig.APPLICATION_ID) continue

                val labels = arrayListOf<AppLabels>()
                if (item.optBoolean("system", false)) labels.add(AppLabels.SYSTEM_APP)
                if (item.optBoolean("modified", false)) labels.add(AppLabels.MODIFIED)
                apps.add(
                    AppInfo(
                        name = item.optString("name", pkg),
                        pkg = pkg,
                        labels = labels,
                        iconVersion = item.optLong("iconVersion", 0L),
                    )
                )
            }
            apps
        }.getOrDefault(emptyList())
    }

    private fun saveCachedAppList(apps: List<AppInfo>) {
        val array = JSONArray()
        for (appInfo in apps) {
            array.put(
                JSONObject()
                    .put("pkg", appInfo.pkg)
                    .put("name", appInfo.name)
                    .put("system", appInfo.isSystemApp())
                    .put("modified", appInfo.isModified())
                    .put("iconVersion", appInfo.iconVersion)
            )
        }
        sharedPreferences.edit()
            .putString(PREF_APP_LIST_CACHE, array.toString())
            .apply()
    }

    private fun updateModifiedLabelsInPlace(apps: MutableList<AppInfo>, modifiedPackages: Set<String>) {
        for (index in apps.indices) {
            val appInfo = apps[index]
            if (appInfo.pkg !in modifiedPackages || appInfo.isModified()) {
                continue
            }
            apps[index] = appInfo.copy(labels = appInfo.labels + AppLabels.MODIFIED)
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
        private const val LOCALE_SCAN_BATCH_SIZE = 80
        private const val LOCALE_SCAN_UPDATE_MIN_INTERVAL_MS = 500L
        private const val LOCALE_SCAN_COOPERATIVE_YIELD_SIZE = 40
        private const val PREF_SHOW_SYSTEM_APPS_HOME = "show_system_apps_home"
        private const val PREF_APP_LIST_CACHE = "app_list_cache"
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
