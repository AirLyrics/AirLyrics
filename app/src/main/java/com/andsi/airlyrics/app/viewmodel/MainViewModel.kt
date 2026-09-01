package com.andsi.airlyrics.app.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.controller.CurrentLyricsDeleteOutcome
import com.andsi.airlyrics.app.controller.FloatingFontImportOutcome
import com.andsi.airlyrics.app.controller.FloatingFontImporter
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.app.controller.LyricsDocumentValidation
import com.andsi.airlyrics.app.controller.LyricsImportOutcome
import com.andsi.airlyrics.app.controller.OnlineLyricsSearchOutcome
import com.andsi.airlyrics.app.state.MainFloatingState
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.app.state.toBundle
import com.andsi.airlyrics.app.state.toPendingLyricsImport
import com.andsi.airlyrics.app.state.toPendingLyricsOverwrite
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.navigation.parentPage
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns main-screen state and state transitions; Android UI work remains in MainGraph. */
internal class MainViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val lyricsController: LyricsController,
    private val foregroundStateReader: MainForegroundStateReader,
    private val floatingFontImporter: FloatingFontImporter
) : ViewModel(), MainFloatingState {
    private val _uiState = MutableStateFlow(restoredState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()
    private val uiEffectChannel = Channel<MainUiEffect>(capacity = Channel.BUFFERED)
    val uiEffects = uiEffectChannel.receiveAsFlow()
    private var mediaRefreshJob: Job? = null
    private val nextSavedLyricsDeletionRequestId = AtomicLong(0L)

    override val locked: Boolean
        get() = _uiState.value.locked

    override val clickThrough: Boolean
        get() = _uiState.value.clickThrough

    override val quickFloatingVisible: Boolean
        get() = _uiState.value.quickFloatingVisible

    override val overlayPermissionGranted: Boolean
        get() = _uiState.value.overlayPermissionGranted

    fun selectPage(page: Page) {
        updateState { state ->
            if (state.currentPage == page) {
                state
            } else {
                state.copy(
                    currentPage = page,
                    settingsSubPage = if (page == Page.SETTINGS) {
                        SettingsSubPage.HOME
                    } else {
                        state.settingsSubPage
                    }
                )
            }
        }
    }

    fun openSettingsSubPage(subPage: SettingsSubPage) {
        updateState { it.copy(settingsSubPage = subPage) }
    }

    fun navigateBack(): Boolean {
        val state = _uiState.value
        if (state.currentPage != Page.SETTINGS || state.settingsSubPage == SettingsSubPage.HOME) {
            return false
        }
        updateState {
            it.copy(settingsSubPage = it.settingsSubPage.parentPage() ?: SettingsSubPage.HOME)
        }
        return true
    }

    fun setSavedLyricsSearchOpen(open: Boolean) {
        updateState { state ->
            state.copy(
                savedLyricsSearchOpen = open,
                savedLyricsSearchQuery = if (open) state.savedLyricsSearchQuery else ""
            )
        }
    }

    fun updateSavedLyricsSearchQuery(query: String) {
        updateState { it.copy(savedLyricsSearchQuery = query) }
    }

    fun setMediaRefreshState(refreshState: RefreshState) {
        updateState(persist = false) { it.copy(mediaRefreshState = refreshState) }
    }

    fun requestOverlayPermission() {
        uiEffectChannel.trySend(MainUiEffect.RequestOverlayPermission)
    }

    fun requestNotificationPermission() {
        uiEffectChannel.trySend(MainUiEffect.RequestNotificationPermission)
    }

    fun openNotificationListenerSettings() {
        uiEffectChannel.trySend(MainUiEffect.OpenNotificationListenerSettings)
    }

    fun selectLyricsDirectory() {
        uiEffectChannel.trySend(MainUiEffect.SelectLyricsDirectory)
    }

    fun selectLyricsFile() {
        uiEffectChannel.trySend(MainUiEffect.SelectLyricsFile)
    }

    fun selectFloatingFontFile() {
        uiEffectChannel.trySend(MainUiEffect.SelectFloatingFontFile)
    }

    fun setLyricsDirectory(uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                lyricsController.setLyricsDirectory(uri)
            }
            if (!saved) {
                showMessage(R.string.ui_lyrics_folder_write_failed, error = true)
                return@launch
            }
            updateState(persist = false) {
                it.copy(lyricsDirectoryRevision = it.lyricsDirectoryRevision + 1L)
            }
            showMessage(R.string.ui_lyrics_save_folder_set)
        }
    }

    fun importFloatingFont(uri: Uri?) {
        uri ?: return
        showMessage(R.string.ui_importing_font)
        viewModelScope.launch {
            when (val outcome = withContext(Dispatchers.IO) {
                floatingFontImporter.import(uri)
            }) {
                is FloatingFontImportOutcome.Success -> {
                    notifyFloatingStructureChanged()
                    uiEffectChannel.trySend(MainUiEffect.FloatingFontImported(outcome.displayName))
                }
                FloatingFontImportOutcome.UnsupportedFormat ->
                    showMessage(R.string.ui_font_format_unsupported, error = true)
                FloatingFontImportOutcome.TooLarge ->
                    showMessage(R.string.ui_font_file_too_large, error = true)
                FloatingFontImportOutcome.InvalidFont ->
                    showMessage(R.string.ui_invalid_font_file, error = true)
                FloatingFontImportOutcome.ReadFailed ->
                    showMessage(R.string.ui_font_import_failed, error = true)
            }
        }
    }

    fun requestLyricsImport() {
        val media = lyricsController.getCurrentMediaInfo()
            ?.takeUnless { it.title.isBlank() }
        if (media == null) {
            showMessage(R.string.ui_select_song_before_importing)
            return
        }
        val target = media.toSongIdentity()
        viewModelScope.launch {
            val availability = withContext(Dispatchers.IO) {
                lyricsController.importAvailability(target)
            }
            uiEffectChannel.trySend(
                MainUiEffect.ShowLyricsImportChoices(
                    target = target,
                    plainImportEnabled = availability.plainImportEnabled,
                    wordByWordImportEnabled = availability.wordByWordImportEnabled
                )
            )
        }
    }

    fun beginLyricsImport(target: SongIdentity, type: LyricsImportType) {
        setPendingLyricsImport(PendingLyricsImport(target = target, type = type))
        selectLyricsFile()
    }

    fun handleLyricsFileResult(uri: Uri?) {
        val request = consumePendingLyricsImport()
        if (uri == null) return
        if (request == null) {
            showMessage(R.string.ui_import_request_expired, error = true)
            return
        }

        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                lyricsController.validatePickedDocument(uri)
            }) {
                LyricsDocumentValidation.UnsupportedFormat -> {
                    showMessage(
                        if (request.type == LyricsImportType.WORD_BY_WORD) {
                            R.string.ui_please_choose_a_word_by_word_lrc_file
                        } else {
                            R.string.ui_please_choose_a_plain_lrc_lyrics_file
                        },
                        error = true
                    )
                }
                LyricsDocumentValidation.TooLarge ->
                    showMessage(R.string.ui_lrc_file_too_large, error = true)
                LyricsDocumentValidation.Valid -> {
                    showMessage(R.string.ui_importing_lyrics)
                    importLyrics(
                        uri = uri,
                        target = request.target,
                        overwrite = false,
                        importAsWordByWord = request.type == LyricsImportType.WORD_BY_WORD
                    )
                }
            }
        }
    }

    fun confirmLyricsOverwrite(expected: PendingLyricsOverwrite) {
        val request = consumePendingLyricsOverwrite(expected) ?: return
        showMessage(R.string.ui_importing_lyrics)
        viewModelScope.launch {
            importLyrics(
                uri = request.uri,
                target = request.target,
                overwrite = true,
                importAsWordByWord = request.type == LyricsImportType.WORD_BY_WORD
            )
        }
    }

    fun searchOnlineLyricsForCurrentMedia() {
        val media = lyricsController.getCurrentMediaInfo()
        if (media == null) {
            showMessage(R.string.ui_no_active_media_found)
            return
        }
        showMessage(R.string.ui_searching_online_again)
        viewModelScope.launch {
            when (val outcome = withContext(Dispatchers.IO) {
                lyricsController.searchOnlineLyricsForCurrentMedia(media)
            }) {
                OnlineLyricsSearchOutcome.Saved ->
                    showMessage(R.string.ui_online_lyrics_saved)
                OnlineLyricsSearchOutcome.NotFound ->
                    showMessage(R.string.ui_lyrics_not_found)
                is OnlineLyricsSearchOutcome.LookupFailed ->
                    uiEffectChannel.trySend(MainUiEffect.ShowLyricsLookupError(outcome.error))
                OnlineLyricsSearchOutcome.Failed ->
                    showMessage(R.string.ui_online_lyrics_search_failed, error = true)
            }
        }
    }

    fun deleteLyricsForCurrentMedia(mode: LyricsStorage.DeleteMode) {
        val media = lyricsController.getCurrentMediaInfo() ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                lyricsController.deleteLyricsForCurrentMedia(media, mode)
            }
            showCurrentLyricsDeleteMessage(outcome)
        }
    }

    fun deleteSavedLyricsItem(item: LyricsStorage.LocalLyricsItem): Long {
        val requestId = nextSavedLyricsDeletionRequestId.incrementAndGet()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                lyricsController.deleteSavedLyricsItem(item)
            }
            notifyLyricsChanged(LyricsStorage.currentRevision())
            val deleted = when (result) {
                is LyricsStorage.DeleteLocalLyricsItemResult.Deleted -> {
                    showMessage(R.string.ui_all_saved_lyrics_deleted)
                    true
                }
                LyricsStorage.DeleteLocalLyricsItemResult.NotFound -> {
                    showMessage(R.string.ui_lyrics_not_found)
                    false
                }
                LyricsStorage.DeleteLocalLyricsItemResult.Failed -> {
                    showMessage(R.string.ui_delete_saved_lyrics_failed, error = true)
                    false
                }
            }
            uiEffectChannel.trySend(
                MainUiEffect.SavedLyricsDeletionCompleted(requestId, deleted)
            )
        }
        return requestId
    }

    fun deleteAllSavedLyrics() {
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) { lyricsController.deleteAllSavedLyrics() }) {
                LyricsStorage.DeleteAllSavedLyricsResult.DELETED ->
                    showMessage(R.string.ui_all_saved_lyrics_deleted)
                LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE ->
                    showMessage(R.string.ui_no_saved_lyrics_to_delete)
                LyricsStorage.DeleteAllSavedLyricsResult.FAILED ->
                    showMessage(R.string.ui_delete_all_saved_lyrics_failed, error = true)
            }
        }
    }

    fun currentMediaInfo(): CurrentMediaInfo? = lyricsController.getCurrentMediaInfo()

    fun lyricsDirectoryPath(): String = lyricsController.lyricsDirectoryPath()

    fun applyForegroundSnapshot(snapshot: ForegroundUiSnapshot) {
        updateState(persist = false) { it.copy(foreground = snapshot) }
    }

    fun refreshForegroundState(): Boolean {
        val overlayWasGranted = _uiState.value.overlayPermissionGranted
        applyForegroundSnapshot(foregroundStateReader.read())
        return !overlayWasGranted && _uiState.value.overlayPermissionGranted
    }

    fun scheduleMediaRefresh(delayMs: Long = MEDIA_REFRESH_DELAY_MS) {
        mediaRefreshJob?.cancel()
        mediaRefreshJob = viewModelScope.launch {
            delay(delayMs.milliseconds)
            refreshForegroundState()
        }
    }

    fun cancelMediaRefresh() {
        mediaRefreshJob?.cancel()
        mediaRefreshJob = null
    }

    override fun updateFloatingState(
        visible: Boolean?,
        overlayGranted: Boolean?,
        locked: Boolean?,
        clickThrough: Boolean?
    ) {
        updateState(persist = false) { state ->
            val permissions = state.foreground.permissions
            val floating = state.foreground.floating
            state.copy(
                foreground = state.foreground.copy(
                    permissions = permissions.copy(
                        overlayGranted = overlayGranted ?: permissions.overlayGranted
                    ),
                    floating = floating.copy(
                        visible = visible ?: floating.visible,
                        locked = locked ?: floating.locked,
                        clickThrough = clickThrough ?: floating.clickThrough
                    )
                )
            )
        }
    }

    fun notifyLyricsChanged(revision: Long) {
        updateState(persist = false) { state ->
            if (state.foreground.lyricsRevision == revision) {
                state
            } else {
                state.copy(
                    foreground = state.foreground.copy(lyricsRevision = revision),
                    lyricsChangeSequence = state.lyricsChangeSequence + 1L
                )
            }
        }
    }

    fun notifyFloatingStructureChanged() {
        updateState(persist = false) {
            it.copy(floatingStructureRevision = it.floatingStructureRevision + 1L)
        }
    }

    fun setPendingLyricsImport(request: PendingLyricsImport?) {
        updateState { it.copy(pendingLyricsImport = request) }
    }

    @Synchronized
    fun consumePendingLyricsImport(): PendingLyricsImport? {
        val request = _uiState.value.pendingLyricsImport ?: return null
        updateState { it.copy(pendingLyricsImport = null) }
        return request
    }

    fun requestLyricsOverwrite(request: PendingLyricsOverwrite) {
        updateState { it.copy(pendingLyricsOverwrite = request) }
    }

    @Synchronized
    fun consumePendingLyricsOverwrite(
        expected: PendingLyricsOverwrite
    ): PendingLyricsOverwrite? {
        val current = _uiState.value.pendingLyricsOverwrite
        if (current != expected) return null
        updateState { it.copy(pendingLyricsOverwrite = null) }
        return current
    }

    @Synchronized
    fun clearPendingLyricsOverwrite(expected: PendingLyricsOverwrite): Boolean {
        if (_uiState.value.pendingLyricsOverwrite != expected) return false
        updateState { it.copy(pendingLyricsOverwrite = null) }
        return true
    }

    private suspend fun importLyrics(
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean,
        importAsWordByWord: Boolean
    ) {
        when (val outcome = withContext(Dispatchers.IO) {
            lyricsController.importLyricsForTarget(
                uri = uri,
                target = target,
                overwrite = overwrite,
                importAsWordByWord = importAsWordByWord
            )
        }) {
            is LyricsImportOutcome.ConfirmationRequired ->
                requestLyricsOverwrite(outcome.request)
            is LyricsImportOutcome.Finished -> handleImportResult(outcome)
        }
    }

    private fun handleImportResult(outcome: LyricsImportOutcome.Finished) {
        when (val result = outcome.result) {
            LyricsStorage.ImportLyricsResult.Saved -> showMessage(
                if (outcome.importAsWordByWord) {
                    R.string.ui_word_by_word_lyrics_import_success
                } else {
                    R.string.ui_plain_lrc_import_success
                }
            )
            LyricsStorage.ImportLyricsResult.TooLarge ->
                showMessage(R.string.ui_lrc_file_too_large, error = true)
            is LyricsStorage.ImportLyricsResult.InvalidFormat ->
                uiEffectChannel.trySend(
                    MainUiEffect.ShowImportFormatError(
                        invalidLineNumbers = result.invalidLineNumbers,
                        wordByWord = outcome.importAsWordByWord
                    )
                )
            LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists ->
                showMessage(R.string.ui_word_by_word_blocked_by_plain_lrc, error = true)
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists ->
                showMessage(R.string.ui_plain_lrc_blocked_by_word_by_word, error = true)
            LyricsStorage.ImportLyricsResult.ReadFailed -> showMessage(
                if (outcome.importAsWordByWord) {
                    R.string.ui_cannot_read_word_by_word_lyrics_file
                } else {
                    R.string.ui_cannot_read_this_lyric_file
                },
                error = true
            )
            LyricsStorage.ImportLyricsResult.SaveFailed,
            LyricsStorage.ImportLyricsResult.SnapshotFailed,
            is LyricsStorage.ImportLyricsResult.RollbackFailed ->
                showMessage(R.string.ui_lrc_import_save_failed, error = true)
        }
    }

    private fun showCurrentLyricsDeleteMessage(outcome: CurrentLyricsDeleteOutcome) {
        val messageRes = if (outcome.deleted) {
            when (outcome.mode) {
                LyricsStorage.DeleteMode.PLAIN -> R.string.ui_plain_lrc_removed_for_this_song
                LyricsStorage.DeleteMode.WORD_BY_WORD -> R.string.ui_word_by_word_lyrics_removed
                LyricsStorage.DeleteMode.ALL -> R.string.ui_all_local_lyrics_removed
            }
        } else {
            when (outcome.mode) {
                LyricsStorage.DeleteMode.PLAIN -> R.string.ui_no_plain_lrc_to_remove_for_this_song
                LyricsStorage.DeleteMode.WORD_BY_WORD -> R.string.ui_no_word_by_word_lyrics_to_remove
                LyricsStorage.DeleteMode.ALL -> R.string.ui_no_local_lyrics_to_remove
            }
        }
        showMessage(messageRes)
    }

    private fun showMessage(
        @androidx.annotation.StringRes messageRes: Int,
        error: Boolean = false
    ) {
        uiEffectChannel.trySend(MainUiEffect.ShowMessage(messageRes, error = error))
    }

    private fun restoredState(): MainScreenState {
        val currentPage = savedStateHandle.get<String>(KEY_CURRENT_PAGE)
            ?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            ?: Page.MEDIA
        val settingsSubPage = savedStateHandle.get<String>(KEY_SETTINGS_SUB_PAGE)
            ?.let { runCatching { SettingsSubPage.valueOf(it) }.getOrNull() }
            ?: SettingsSubPage.HOME
        return MainScreenState(
            currentPage = currentPage,
            settingsSubPage = settingsSubPage,
            savedLyricsSearchOpen = savedStateHandle[KEY_SAVED_LYRICS_SEARCH_OPEN] ?: false,
            savedLyricsSearchQuery = savedStateHandle[KEY_SAVED_LYRICS_SEARCH_QUERY] ?: "",
            pendingLyricsImport = savedStateHandle.get<Bundle>(KEY_PENDING_LYRICS_IMPORT)
                ?.toPendingLyricsImport(),
            pendingLyricsOverwrite = savedStateHandle.get<Bundle>(KEY_PENDING_LYRICS_OVERWRITE)
                ?.toPendingLyricsOverwrite()
        )
    }

    private fun updateState(
        persist: Boolean = true,
        transform: (MainScreenState) -> MainScreenState
    ) {
        _uiState.update(transform)
        if (persist) persistState(_uiState.value)
    }

    private fun persistState(state: MainScreenState) {
        savedStateHandle[KEY_CURRENT_PAGE] = state.currentPage.name
        savedStateHandle[KEY_SETTINGS_SUB_PAGE] = state.settingsSubPage.name
        savedStateHandle[KEY_SAVED_LYRICS_SEARCH_OPEN] = state.savedLyricsSearchOpen
        savedStateHandle[KEY_SAVED_LYRICS_SEARCH_QUERY] = state.savedLyricsSearchQuery
        savedStateHandle[KEY_PENDING_LYRICS_IMPORT] = state.pendingLyricsImport?.toBundle()
        savedStateHandle[KEY_PENDING_LYRICS_OVERWRITE] = state.pendingLyricsOverwrite?.toBundle()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val mediaControllerProvider = object : MediaControllerProvider {
                        override fun getActiveControllers() =
                            CurrentMediaReader.getActiveControllers(appContext)
                    }
                    MainViewModel(
                        savedStateHandle = createSavedStateHandle(),
                        lyricsController = LyricsController(
                            context = appContext,
                            mediaControllerProvider = mediaControllerProvider,
                            lyricsChangedPublisher = BroadcastLyricsChangedPublisher(appContext)
                        ),
                        foregroundStateReader = MainForegroundStateReader(appContext),
                        floatingFontImporter = FloatingFontImporter(appContext)
                    )
                }
            }
        }

        private const val KEY_CURRENT_PAGE = "airlyrics.current_page"
        private const val KEY_SETTINGS_SUB_PAGE = "airlyrics.settings_sub_page"
        private const val KEY_SAVED_LYRICS_SEARCH_OPEN = "airlyrics.saved_lyrics_search_open"
        private const val KEY_SAVED_LYRICS_SEARCH_QUERY = "airlyrics.saved_lyrics_search_query"
        private const val KEY_PENDING_LYRICS_IMPORT = "airlyrics.pending_lyrics_import"
        private const val KEY_PENDING_LYRICS_OVERWRITE = "airlyrics.pending_lyrics_overwrite"
        private const val MEDIA_REFRESH_DELAY_MS = 120L
    }
}
