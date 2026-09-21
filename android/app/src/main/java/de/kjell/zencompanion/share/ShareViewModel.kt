package de.kjell.zencompanion.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.BrowserSettings
import de.kjell.zencompanion.data.SaveKind
import de.kjell.zencompanion.data.SnapshotCache
import de.kjell.zencompanion.sync.SpacesSyncService
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.components.PinDestinationModel
import de.kjell.zencompanion.util.FriendlyError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the share flow needs from the outside world: session check,
 * cached/fresh snapshots and the tab write.
 */
interface ShareRepository {
    fun isSignedIn(): Boolean
    val cachedSnapshot: ZenSpaces.ZenSnapshot?
    fun lastSpaceId(): String?
    fun setLastSpaceId(id: String?)
    suspend fun refresh(): ZenSpaces.ZenSnapshot
    suspend fun addTab(
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind = SaveKind.PINNED,
    ): SpacesSyncService.AddTabOutcome
    fun errorText(error: Exception): String
    /** Shown when a save is attempted while no spaces exist to pin into. */
    fun noSpacesErrorText(): String
    /** Global pinned/normal choice for newly saved tabs. */
    fun saveKind(): SaveKind
}

internal class AndroidShareRepository(context: Context) : ShareRepository {
    private val appContext = context.applicationContext
    override fun isSignedIn(): Boolean = AccountStore.isSignedIn(appContext)
    override val cachedSnapshot: ZenSpaces.ZenSnapshot?
        get() = SnapshotCache.cachedSnapshotShared
    override fun lastSpaceId(): String? = SnapshotCache.lastSpaceId(appContext)
    override fun setLastSpaceId(id: String?) = SnapshotCache.setLastSpaceId(appContext, id)
    override suspend fun refresh(): ZenSpaces.ZenSnapshot = SpacesSyncService.refresh(appContext)
    override suspend fun addTab(
        url: String,
        title: String,
        spaceId: String,
        folderId: String?,
        kind: SaveKind,
    ): SpacesSyncService.AddTabOutcome =
        SpacesSyncService.addTab(appContext, url, title, spaceId, folderId, kind)
    override fun errorText(error: Exception): String =
        appContext.getString(FriendlyError.messageRes(error))
    override fun noSpacesErrorText(): String = appContext.getString(R.string.share_no_spaces)
    override fun saveKind(): SaveKind = BrowserSettings.getSaveKind(appContext)
}

/**
 * Port of `ShareActivity`'s bootstrap/refresh/save state machine: instant
 * cached pick, background refresh, deferred save with a 480ms auto-close.
 */
class ShareViewModel(
    private val repository: ShareRepository,
    initialUrl: String?,
    initialPageTitle: String,
) : ViewModel() {

    sealed interface Event {
        /** The sheet should close (save finished or the user cancelled). */
        data object Finished : Event
    }

    data class State(
        val url: String? = null,
        val pageTitle: String = "",
        val spaces: List<ZenSpaces.ZenSpace> = emptyList(),
        val destination: PinDestination? = null,
        val phase: SharePhase = SharePhase.loading,
        val errorText: String? = null,
        /** Pinned (default) or normal tab for this save. */
        val saveKind: SaveKind = SaveKind.PINNED,
        /** True when the requested normal save fell back to a pinned record. */
        val savedAsPinnedFallback: Boolean = false,
    ) {
        /** Normal saves always target the space root, so folders are hidden. */
        val hideFolders: Boolean get() = saveKind == SaveKind.NORMAL
    }

    private val _state = MutableStateFlow(
        State(
            url = initialUrl,
            pageTitle = initialPageTitle,
            saveKind = repository.saveKind(),
        ),
    )
    val state: StateFlow<State> = _state

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events

    init {
        viewModelScope.launch { bootstrap() }
    }

    fun selectDestination(destination: PinDestination) {
        // Normal tabs always land at the space root; never keep a folder.
        val forced = if (_state.value.hideFolders) destination.copy(folderId = null) else destination
        _state.update { it.copy(destination = forced) }
    }

    fun save() {
        viewModelScope.launch { performSave() }
    }

    fun cancel() {
        _events.tryEmit(Event.Finished)
    }

    /** Port of `bootstrap()` — instant cached pick, then a background refresh. */
    suspend fun bootstrap() {
        if (!repository.isSignedIn()) {
            _state.update { it.copy(phase = SharePhase.signedOut) }
            return
        }

        repository.cachedSnapshot?.let { cached ->
            if (cached.spaces.isNotEmpty()) {
                _state.update {
                    it.copy(
                        spaces = cached.spaces,
                        destination = lastDestination(cached.spaces),
                        phase = SharePhase.pick,
                    )
                }
            }
        }

        // Fresh data in the background; replaces the cache when it lands.
        refreshSpaces()
    }

    /** Port of `refreshSpaces()`. */
    suspend fun refreshSpaces() {
        try {
            val fresh = repository.refresh()
            if (fresh.spaces.isEmpty()) return
            val current = _state.value
            val currentId = current.destination?.spaceId
            val destination = if (currentId == null || fresh.spaces.none { it.id == currentId }) {
                lastDestination(fresh.spaces)
            } else if (current.hideFolders) {
                PinDestination(spaceId = currentId)
            } else {
                val folderId = current.destination?.folderId
                if (!folderId.isNullOrEmpty()) {
                    val space = fresh.spaces.firstOrNull { it.id == currentId }
                    if (space == null || PinDestinationModel.folderName(folderId, space) == null) {
                        // Folder vanished on the fresh pull: fall back to the root.
                        PinDestination(spaceId = currentId)
                    } else {
                        current.destination
                    }
                } else {
                    current.destination
                }
            }
            _state.update {
                it.copy(
                    spaces = fresh.spaces,
                    destination = destination,
                    phase = if (it.phase != SharePhase.saved) SharePhase.pick else it.phase,
                )
            }
        } catch (e: Exception) {
            if (_state.value.spaces.isEmpty()) {
                _state.update {
                    it.copy(
                        errorText = repository.errorText(e),
                        phase = if (repository.isSignedIn()) SharePhase.failed else SharePhase.signedOut,
                    )
                }
            }
        }
    }

    /**
     * Only the space is remembered across shares — never the folder. Every
     * share starts at the space root.
     */
    private fun lastDestination(spaces: List<ZenSpaces.ZenSpace>): PinDestination {
        val lastId = repository.lastSpaceId()
        if (lastId != null && spaces.any { it.id == lastId }) {
            return PinDestination(spaceId = lastId)
        }
        return spaces.firstOrNull()?.let { PinDestination.of(it) } ?: PinDestination(spaceId = "")
    }

    /** Port of `save()` — saved state, then auto-close after 480ms. */
    private suspend fun performSave() {
        val current = _state.value
        val currentUrl = current.url ?: return
        // No spaces to attach the pin to: refuse rather than write an
        // unattached record.
        if (current.spaces.isEmpty()) {
            _state.update {
                it.copy(errorText = repository.noSpacesErrorText(), phase = SharePhase.failed)
            }
            return
        }
        val target = current.destination ?: return
        val targetSpace = current.spaces.firstOrNull { it.id == target.spaceId } ?: return
        val kind = current.saveKind
        _state.update { it.copy(phase = SharePhase.saving) }
        try {
            val outcome = repository.addTab(
                url = currentUrl,
                title = headlineTitle(current.pageTitle, currentUrl),
                spaceId = targetSpace.id,
                folderId = if (kind == SaveKind.NORMAL) null else target.folderId,
                kind = kind,
            )
            repository.setLastSpaceId(targetSpace.id)
            _state.update {
                it.copy(
                    phase = SharePhase.saved,
                    savedAsPinnedFallback = outcome.fellBackToPinned,
                )
            }
            // A fallback notice needs longer on screen than the plain
            // confirmation before the sheet auto-closes.
            delay(if (outcome.fellBackToPinned) 2_400 else 480)
            _events.emit(Event.Finished)
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    errorText = repository.errorText(e),
                    phase = SharePhase.failed,
                )
            }
        }
    }

    class Factory(
        private val repository: ShareRepository,
        private val initialUrl: String?,
        private val initialPageTitle: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ShareViewModel(repository, initialUrl, initialPageTitle) as T
    }
}
