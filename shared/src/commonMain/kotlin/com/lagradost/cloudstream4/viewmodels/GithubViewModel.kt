package com.lagradost.cloudstream4.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.state.ActionHandler
import com.lagradost.cloudstream4.state.DefaultStateContainer
import com.lagradost.cloudstream4.state.SingleActiveQuery
import com.lagradost.cloudstream4.state.StateContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class GithubState(
    val dialog: GithubDialog? = null,
)

@Immutable
sealed class GithubUpdateDialogState {
    data class Error(val error: Throwable) : GithubUpdateDialogState()
    data class DownloadProgress(val progress: Long, val total: Long?) : GithubUpdateDialogState()
    data class InstallProgress(val progress: Long, val total: Long?) : GithubUpdateDialogState()
    object Loading : GithubUpdateDialogState()
    object NoUpdateFound : GithubUpdateDialogState()
    data class UpdateFound(
        val file: GithubFile,
        val newSha: String?,
        val oldSha: String?,
    ) : GithubUpdateDialogState()
}

@Immutable
data class GithubDialog(
    val isPrerelease: Boolean,
    val isFromUser: Boolean,
    val state: GithubUpdateDialogState,
)

/** GitHub file update package */
@Immutable
data class GithubFile(
    /** File digest, sha:xxx */
    val digest: String?,
    /** File url for download */
    val downloadUrl: String,
    /** Filename without the extension */
    val displayName: String,
    /** Changelog, aka the commit message */
    val changeLog: String,
    /** Name of the tag, aka unique release name like vX.X.X or pre-release */
    val tagName: String,
    /** Unique node id */
    val nodeId: String,
)

@Immutable
sealed class GithubAction {
    object AutoSearchForUpdate : GithubAction()
    object SearchForUpdate : GithubAction()
    object Dismiss : GithubAction()
    data class SkipThisUpdate(val file: GithubFile) : GithubAction()
    data class Update(val file: GithubFile) : GithubAction()
    data class SkipUpdate(val file: GithubFile) : GithubAction()
}

interface AppUpdater {
    @Throws
    suspend fun update(
        settings: AppSettings,
        url: String,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit,
    )

    /**
     * Downloads an update apk quietly in the background and caches it.
     * Returns the absolute path of the cached file, or null on failure.
     * If a valid cached file already exists, returns its path immediately.
     */
    suspend fun downloadSilently(
        url: String,
        versionTag: String,
        digest: DigestPair?,
    ): String?

    /**
     * Installs an update from a previously cached apk file.
     * Returns true when the install was started, false when the cached file
     * failed digest validation (it is deleted so a fresh download can follow).
     */
    @Throws
    suspend fun installFromFile(
        filePath: String,
        settings: AppSettings,
        digest: DigestPair?,
        installProgress: (Long, Long?) -> Unit,
    ): Boolean

    /**
     * Returns the absolute path of the cached update apk for the given version,
     * or null when nothing is cached.
     */
    fun getCachedUpdatePath(versionTag: String): String?

    /**
     * Deletes the cached update apk for the given version, if present.
     */
    fun deleteCachedUpdate(versionTag: String)
}

interface GithubRepository {
    @Throws
    suspend fun getRelease(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile

    @Throws
    suspend fun getSha(
        userName: String,
        repository: String,
        tag: String,
    ): String
}

/** The digest pair to verify that a file is correctly downloaded */
data class DigestPair(
    val algorithm: String,
    val digest: ByteArray,
) {
    companion object {
        // "sha256:XXXX" or "sha256-XXXX" -> "sha256", bytearray(XXXX)
        fun parse(digestPair: String?): DigestPair? {
            if (digestPair == null) return null

            var split = digestPair.split(":", limit = 2)
            if (split.size != 2) {
                split = digestPair.split("-", limit = 2)
            }
            if (split.size != 2) {
                return null
            }

            val digestAlgorithm = split.getOrNull(0) ?: return null
            val digestByteArray = safe { split.getOrNull(1)?.hexToByteArray() } ?: return null

            return DigestPair(digestAlgorithm, digestByteArray)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val other = other as? DigestPair ?: return false

        if (algorithm != other.algorithm) return false
        if (!digest.contentEquals(other.digest)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + digest.contentHashCode()
        return result
    }
}

/**
 * Cross-platform downloader for updates served by GitHub.
 *
 * To allow this Cross-platform behavior work we split up the UI from the Viewmodel,
 * and the Viewmodel from the installer.
 * ```
 * UI:          Renders the current viewmodel state
 * Viewmodel:   Searches for updates on GitHub
 * AppUpdater:  Installs the update from the raw GitHub url
 *
 * UI -(onAction)-> Viewmodel -(invokes)-> AppUpdater
 *   <---(state)---/        <--(callback)--/
 * ```
 * */
class GithubViewModel(
    val remoteUserName: String,
    val remoteRepository: String,
    val remotePrereleaseTag: String,
    val remoteContentType: String,
    val versionName: String,
    val isPrerelease: Boolean,
    val isDebug: Boolean,
    val buildSha: String,
    val settings: AppSettings,
    val updater: AppUpdater,
    val repository: GithubRepository,
) : ViewModel(), StateContainer<GithubState> by DefaultStateContainer(GithubState()),
    ActionHandler<GithubAction> {
    private val updateDispatcher = SingleActiveQuery(Dispatchers.IO)

    override fun onAction(action: GithubAction) {
        when (action) {
            GithubAction.SearchForUpdate -> {
                ioSafe {
                    searchForUpdate(prerelease = isPrerelease, fromUser = true)
                }
            }

            GithubAction.Dismiss -> {
                viewModelScope.launch {
                    updateDispatcher.cancel()
                    updateState { copy(dialog = null) }
                }
            }

            is GithubAction.SkipThisUpdate -> {
                settings.updates.skipUpdate.set(action.file.nodeId)
                updater.deleteCachedUpdate(action.file.tagName)
            }

            GithubAction.AutoSearchForUpdate -> {
                if (!isDebug && settings.updates.showAppUpdates.get()) {
                    ioSafe {
                        searchForUpdate(prerelease = isPrerelease, fromUser = false)
                    }
                }
            }

            is GithubAction.SkipUpdate -> {
                settings.updates.skipUpdate.set(action.file.nodeId)
                updater.deleteCachedUpdate(action.file.tagName)
            }

            is GithubAction.Update -> {
                ioSafe {
                    installUpdate(action.file)
                }
            }
        }
    }

    /** Cancel the old update, and catch possible errors from the block and show as a new state */
    private suspend fun dispatchUpdate(block: /* @Throws */ suspend () -> Unit) {
        updateDispatcher.launch {
            try {
                block()
            } catch (t: Throwable) {
                // If it was canceled ignore it as we probably launched another update check
                if (!isActive) {
                    return@launch
                }
                // Otherwise we display the error
                updateState {
                    copy(dialog = dialog?.copy(state = GithubUpdateDialogState.Error(t)))
                }
            }
        }
    }

    private suspend fun installUpdate(file: GithubFile) = dispatchUpdate {
        val digest = DigestPair.parse(file.digest)
        val cachedPath = updater.getCachedUpdatePath(file.tagName)

        // Install from the pre-downloaded cache when it exists and passes digest
        // validation; otherwise download the update fresh.
        val installedFromCache = if (cachedPath != null) {
            // APK was pre-downloaded in the background, install straight from cache
            updater.installFromFile(
                filePath = cachedPath,
                settings = settings,
                digest = digest,
            ) { progress, total ->
                updateState {
                    copy(
                        dialog = dialog?.copy(
                            state = GithubUpdateDialogState.InstallProgress(
                                progress = progress,
                                total = total
                            )
                        )
                    )
                }
            }
        } else {
            false
        }

        if (!installedFromCache) {
            updater.update(
                settings = settings,
                url = file.downloadUrl,
                digest = digest,
            ) { progress, total ->
                updateState {
                    copy(
                        dialog = dialog?.copy(
                            state = GithubUpdateDialogState.DownloadProgress(
                                progress = progress,
                                total = total
                            )
                        )
                    )
                }
            }
        }
        updateState {
            copy(
                dialog = null
            )
        }
    }

    private suspend fun searchForUpdate(
        prerelease: Boolean,
        fromUser: Boolean,
    ) = dispatchUpdate {
        val baseDialog = GithubDialog(
            isPrerelease = prerelease,
            isFromUser = fromUser,
            state = GithubUpdateDialogState.Loading
        )

        updateState {
            copy(dialog = baseDialog)
        }

        // If on pre-release check if the sha matches, as we do not look at the version
        var oldSha: String? = null
        var newSha: String? = null
        if (prerelease) {
            val sha = repository.getSha(
                tag = remotePrereleaseTag,
                userName = remoteUserName,
                repository = remoteRepository,
            )
            oldSha = buildSha.take(7)
            newSha = sha.take(7)

            // Only match the first 7 chars, as that is what is saved
            if (oldSha == newSha) {
                updateState {
                    copy(dialog = baseDialog.copy(state = GithubUpdateDialogState.NoUpdateFound))
                }
                return@dispatchUpdate
            }
        }

        val release = repository.getRelease(
            prerelease = prerelease,
            userName = remoteUserName,
            repository = remoteRepository,
            prereleaseTag = remotePrereleaseTag,
            contentType = remoteContentType
        )

        // If on stable, only check that the display name matches
        if (!prerelease && release.displayName == versionName) {
            updateState {
                copy(dialog = baseDialog.copy(state = GithubUpdateDialogState.NoUpdateFound))
            }
            return@dispatchUpdate
        }

        // If this was automated, and we have pressed "skip this update" then check the node-id
        if (!fromUser && release.nodeId == settings.updates.skipUpdate.get()) {
            updateState {
                copy(dialog = baseDialog.copy(state = GithubUpdateDialogState.NoUpdateFound))
            }
            return@dispatchUpdate
        }

        // Automated check found an update: fetch the apk quietly now so that
        // tapping "Update" later installs instantly without waiting on a download.
        if (!fromUser) {
            if (prerelease) {
                // The pre-release tag is floating: a cached apk under this tag may
                // belong to an older commit, so drop it before re-downloading.
                updater.deleteCachedUpdate(release.tagName)
            }
            updater.downloadSilently(
                url = release.downloadUrl,
                versionTag = release.tagName,
                digest = DigestPair.parse(release.digest),
            )
        }

        updateState {
            copy(
                dialog = baseDialog.copy(
                    state = GithubUpdateDialogState.UpdateFound(
                        file = release,
                        newSha = newSha,
                        oldSha = oldSha
                    )
                )
            )
        }
    }
}