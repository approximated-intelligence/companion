package de.perigon.companion.backup.ui.restore

import de.perigon.companion.backup.data.BackupRestoreView

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

/**
 * A single restorable version of a file. Multiple versions exist when the
 * same path was backed up at different (mtime, size) combinations.
 */
data class RestoreFileVersion(
    val id: Long,
    val path: String,
    val size: Long,
    val mtime: Long,
    val sha256: String,
    val startPack: Int,
    val startPart: Int,
    val startPartOffset: Long,
    val endPack: Int,
    val endPart: Int,
    val numParts: Int,
    val selected: Boolean,
)

/** Tri-state for folder checkboxes. */
enum class SelectionState { NONE, SOME, ALL }

/**
 * A node in the restore file tree. Either a folder or a leaf file (which may
 * have multiple versions).
 */
sealed class TreeNode {
    abstract val name: String
    abstract val selectionState: SelectionState

    data class Folder(
        override val name: String,
        val children: List<TreeNode>,
        val expanded: Boolean = true,
        override val selectionState: SelectionState,
    ) : TreeNode()

    data class File(
        override val name: String,
        /** Versions sorted newest-first. */
        val versions: List<RestoreFileVersion>,
        val versionsExpanded: Boolean = false,
        override val selectionState: SelectionState,
    ) : TreeNode()
}

// ---------------------------------------------------------------------------
// Tree construction (pure)
// ---------------------------------------------------------------------------

/**
 * Build a [TreeNode.Folder] tree from a flat list of [BackupRestoreView].
 *
 * - Groups by path components.
 * - Multiple rows with the same path → multiple [RestoreFileVersion] under one [TreeNode.File].
 * - Deduplicates rows sharing the same (path, sha256), keeping the oldest backup location.
 * - Selects the most-recent version of each file by default.
 */
fun buildRestoreTree(rows: List<BackupRestoreView>): TreeNode.Folder {
    // Group versions by path
    val byPath: Map<String, List<BackupRestoreView>> = rows.groupBy { it.path }

    // Convert to RestoreFileVersion lists, newest-first, most-recent pre-selected.
    // Deduplicate: if multiple rows share the same (path, sha256), keep only the one
    // with the lowest startPack (oldest/first backup location) to avoid duplicate
    // LazyColumn keys and redundant restore entries.
    val versionsByPath: Map<String, List<RestoreFileVersion>> = byPath.mapValues { (_, views) ->
        val deduped = views
            .groupBy { it.sha256 }
            .values
            .map { group -> group.minBy { it.startPack } }
        val sorted = deduped.sortedByDescending { it.mtime }
        sorted.mapIndexed { idx, v ->
            RestoreFileVersion(
                id = v.id,
                path = v.path,
                size = v.size,
                mtime = v.mtime,
                sha256 = v.sha256,
                startPack = v.startPack,
                startPart = v.startPart,
                startPartOffset = v.startPartOffset,
                endPack = v.endPack,
                endPart = v.endPart,
                numParts = v.numParts,
                selected = idx == 0, // newest selected by default
            )
        }
    }

    return buildFolder("", versionsByPath, emptyList())
}

private fun buildFolder(
    name: String,
    versionsByPath: Map<String, List<RestoreFileVersion>>,
    prefixParts: List<String>,
): TreeNode.Folder {
    // Partition into direct children (no more slashes) vs deeper paths
    val directFiles = mutableMapOf<String, List<RestoreFileVersion>>()
    val subfolderPaths = mutableMapOf<String, MutableMap<String, List<RestoreFileVersion>>>()

    for ((path, versions) in versionsByPath) {
        val parts = path.split("/")
        val relParts = if (prefixParts.isEmpty()) parts else parts.drop(prefixParts.size)
        when {
            relParts.size == 1 -> directFiles[relParts[0]] = versions
            relParts.size > 1  -> {
                val folderName = relParts[0]
                subfolderPaths.getOrPut(folderName) { mutableMapOf() }[path] = versions
            }
        }
    }

    val children = mutableListOf<TreeNode>()

    // Subfolders first, sorted
    for ((folderName, subPaths) in subfolderPaths.entries.sortedBy { it.key }) {
        children += buildFolder(folderName, subPaths, prefixParts + folderName)
    }

    // Then files, sorted
    for ((fileName, versions) in directFiles.entries.sortedBy { it.key }) {
        val selState = when {
            versions.all { it.selected }  -> SelectionState.ALL
            versions.any { it.selected }  -> SelectionState.SOME
            else                           -> SelectionState.NONE
        }
        children += TreeNode.File(
            name = fileName,
            versions = versions,
            versionsExpanded = versions.size > 1,
            selectionState = selState,
        )
    }

    val folderSel = folderSelectionState(children)
    return TreeNode.Folder(name = name, children = children, selectionState = folderSel)
}

private fun folderSelectionState(children: List<TreeNode>): SelectionState {
    if (children.isEmpty()) return SelectionState.NONE
    val states = children.map { it.selectionState }.toSet()
    return when {
        states == setOf(SelectionState.ALL)  -> SelectionState.ALL
        states == setOf(SelectionState.NONE) -> SelectionState.NONE
        else                                  -> SelectionState.SOME
    }
}

// ---------------------------------------------------------------------------
// Tree mutation helpers (pure)
// ---------------------------------------------------------------------------

/** Toggle selection of a specific version by its sha256+path key. */
fun TreeNode.Folder.toggleVersion(path: String, sha256: String): TreeNode.Folder =
    mapVersions { version ->
        if (version.path == path && version.sha256 == sha256)
            version.copy(selected = !version.selected)
        else version
    }.recomputeSelection()

/**
 * Set all versions under a folder path prefix to [selected].
 * Operates on all descendants recursively.
 */
fun TreeNode.Folder.setFolderSelected(folderPath: String, selected: Boolean): TreeNode.Folder =
    mapVersions { version ->
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        if (folderPath.isEmpty() || version.path.startsWith(prefix) || version.path == folderPath)
            version.copy(selected = selected)
        else version
    }.recomputeSelection()

/** Select all / deselect all — operates on the root. */
fun TreeNode.Folder.setAllSelected(selected: Boolean): TreeNode.Folder =
    setFolderSelected("", selected)

/** Expand/collapse a folder node at a given path. */
fun TreeNode.Folder.toggleFolderExpanded(folderPath: String): TreeNode.Folder =
    transformFolders { folder, path ->
        if (path == folderPath) folder.copy(expanded = !folder.expanded) else folder
    }

/** Expand/collapse version list for a file. */
fun TreeNode.Folder.toggleVersionsExpanded(filePath: String): TreeNode.Folder =
    transformFiles { file ->
        if (file.versions.firstOrNull()?.path == filePath)
            file.copy(versionsExpanded = !file.versionsExpanded)
        else file
    }

// ---------------------------------------------------------------------------
// Private recursive helpers
// ---------------------------------------------------------------------------

private fun TreeNode.Folder.mapVersions(
    transform: (RestoreFileVersion) -> RestoreFileVersion,
): TreeNode.Folder {
    val newChildren = children.map { child ->
        when (child) {
            is TreeNode.Folder -> child.mapVersions(transform)
            is TreeNode.File   -> child.copy(versions = child.versions.map(transform))
        }
    }
    return copy(children = newChildren)
}

private fun TreeNode.Folder.recomputeSelection(): TreeNode.Folder {
    val newChildren = children.map { child ->
        when (child) {
            is TreeNode.Folder -> {
                val recomputed = child.recomputeSelection()
                recomputed
            }
            is TreeNode.File -> {
                val sel = when {
                    child.versions.all { it.selected }  -> SelectionState.ALL
                    child.versions.any { it.selected }  -> SelectionState.SOME
                    else                                  -> SelectionState.NONE
                }
                child.copy(selectionState = sel)
            }
        }
    }
    return copy(children = newChildren, selectionState = folderSelectionState(newChildren))
}

private fun TreeNode.Folder.transformFolders(
    path: String = name,
    transform: (TreeNode.Folder, String) -> TreeNode.Folder,
): TreeNode.Folder {
    val transformed = transform(this, path)
    val newChildren = transformed.children.map { child ->
        when (child) {
            is TreeNode.Folder -> {
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                child.transformFolders(childPath, transform)
            }
            is TreeNode.File -> child
        }
    }
    return transformed.copy(children = newChildren)
}

private fun TreeNode.Folder.transformFiles(
    transform: (TreeNode.File) -> TreeNode.File,
): TreeNode.Folder {
    val newChildren = children.map { child ->
        when (child) {
            is TreeNode.Folder -> child.transformFiles(transform)
            is TreeNode.File   -> transform(child)
        }
    }
    return copy(children = newChildren)
}

// ---------------------------------------------------------------------------
// Extraction helper
// ---------------------------------------------------------------------------

/** Flatten tree to selected versions only. */
fun TreeNode.Folder.selectedVersions(): List<RestoreFileVersion> {
    val result = mutableListOf<RestoreFileVersion>()
    fun collect(node: TreeNode) {
        when (node) {
            is TreeNode.Folder -> node.children.forEach(::collect)
            is TreeNode.File   -> result += node.versions.filter { it.selected }
        }
    }
    children.forEach(::collect)
    return result
}

/** Count of selected versions across the entire tree. */
fun TreeNode.Folder.selectedCount(): Int = selectedVersions().size

fun RestoreFileVersion.toRestoreView() = de.perigon.companion.backup.data.BackupRestoreView(
    id = id,
    path = path,
    mtime = mtime,
    size = size,
    sha256 = sha256,
    startPack = startPack,
    startPart = startPart,
    startPartOffset = startPartOffset,
    endPack = endPack,
    endPart = endPart,
    numParts = numParts,
)
