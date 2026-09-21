package de.perigon.companion.core.ui.access

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.core.feature.AccessRequirement
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.distinctRequirements
import de.perigon.companion.core.feature.isSatisfied
import de.perigon.companion.core.prefs.CorePrefs
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.saf.folderLabel as labelOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything the access UI needs: the union of requirements of the features
 * in this build, folder grant storage, and the first-start flag.
 */
@HiltViewModel
class AccessViewModel @Inject constructor(
    private val folderGrants: FolderGrants,
    private val corePrefs: CorePrefs,
    features: Set<@JvmSuppressWildcards FeatureEntry>,
) : ViewModel() {

    val all: List<AccessRequirement> =
        features.sortedBy { it.order }.flatMap { it.requirements }.distinctRequirements()

    val setupDone: StateFlow<Boolean> = corePrefs.observeSetupDone()
        .stateIn(viewModelScope, SharingStarted.Eagerly, corePrefs.setupDone())

    fun folderUri(key: String): String? = folderGrants.uri(key)

    fun folderLabel(key: String): String? = folderGrants.label(key)

    fun isSatisfied(ctx: Context, req: AccessRequirement): Boolean =
        req.isSatisfied(ctx, ::folderUri)

    suspend fun setFolder(key: String, uri: Uri) =
        folderGrants.set(key, uri.toString(), labelOf(uri))

    suspend fun clearFolder(key: String) = folderGrants.clear(key)

    fun completeSetup() {
        viewModelScope.launch { corePrefs.setSetupDone(true) }
    }
}
