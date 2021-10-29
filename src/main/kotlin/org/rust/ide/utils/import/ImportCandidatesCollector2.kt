/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.utils.import

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.util.containers.MultiMap
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.util.AutoInjectedCrates
import org.rust.ide.injected.isDoctestInjection
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CrateGraphService
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.crate.crateGraph
import org.rust.lang.core.psi.RsCodeFragmentFactory
import org.rust.lang.core.psi.RsFile.Attributes
import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.Namespace
import org.rust.lang.core.resolve.TYPES_N_VALUES
import org.rust.lang.core.resolve.ref.deepResolve
import org.rust.lang.core.resolve2.*
import org.rust.stdext.mapToSet

private val AUTO_IMPORT_USING_NEW_RESOLVE: RegistryValue = Registry.get("org.rust.auto.import.using.new.resolve")
private val Project.useAutoImportWithNewResolve: Boolean get() = isNewResolveEnabled && AUTO_IMPORT_USING_NEW_RESOLVE.asBoolean()
val RsElement.useAutoImportWithNewResolve: Boolean get() = project.useAutoImportWithNewResolve && !isDoctestInjection

/**
 * ## High-level description
 * Consider we have code like:
 * ```rust
 * fn main() {
 *     func();
 * }
 * ```
 * And want to find import for `func`.
 * Import will have path like `mod1::mod2::mod3::modn::func`.
 * - `mod1` is always crate root - either current crate or one of its dependencies.
 *   See [getInitialDefMapsToSearch].
 * - Given candidates for `mod{n}` we can find candidates for `mod{n+1}` among [ModData.visibleItems] for `mod{n}`.
 *   See [getAllModPaths].
 * - Finally when we have path `mod1::mod2::mod3::modn`,
 *   we filter items by name in [ModData.visibleItems] for `modn`,
 *   and receive result path `mod1::mod2::mod3::modn::func`.
 *   See [getAllItemPathsInMod].
 *
 * ### Filtration (when we find multiple paths for single item)
 * - If we can use path to crate where item is declared, then we ignore reexports in all other crates - see [filterForSingleCrate].
 * - If there are multiple paths in single crate, then we choose the shortest one - see [filterShortestPath].
 * - Private reexports are not used - see [checkVisibility].
 *
 * ### Sorting
 * We sort items in following order:
 * - Std
 * - Workspace
 * - Dependencies
 * See [ImportCandidate2.compareTo].
 */
object ImportCandidatesCollector2 {
    fun getImportCandidates(context: ImportContext2, targetName: String): List<ImportCandidate2> {
        val itemsPaths = context.getAllModPaths()
            .flatMap { context.getAllItemPathsInMod(it, targetName) }
        return context.convertToCandidates(itemsPaths)
    }

    fun getCompletionCandidates(
        context: ImportContext2,
        prefixMatcher: PrefixMatcher,
        processedElements: MultiMap<String, RsElement>
    ): List<ImportCandidate2> {
        val modPaths = context.getAllModPaths()
        val allNames = modPaths.flatMapTo(hashSetOf()) { it.mod.visibleItems.keys }
        val nameToPriority = prefixMatcher.sortMatching(allNames)
            .withIndex().associate { (index, value) -> value to index }
        val itemsPaths = modPaths
            .flatMap { context.getAllItemPathsInMod(it, nameToPriority::containsKey) }
        return context.convertToCandidates(itemsPaths)
            /** we need this filter in addition to [hasVisibleItemInRootScope] because there can be local imports */
            .filter { it.qualifiedNamedItem.item !in processedElements[it.qualifiedNamedItem.itemName] }
            .sortedBy { nameToPriority[it.qualifiedNamedItem.itemName] }
    }
}

private fun ImportContext2.convertToCandidates(itemsPaths: List<ItemUsePath>): List<ImportCandidate2> =
    itemsPaths
        .groupBy { it.toItemWithNamespace() }
        .mapValues { (item, paths) -> filterForSingleItem(paths, item) }
        .flatMap { (item, paths) ->
            val itemsPsi = item
                .toPsi(rootDefMap, project)
                .filterIsInstance<RsQualifiedNamedElement>()
                .filter(namespaceFilter)
            // cartesian product of `itemsPsi` and `paths`
            itemsPsi.flatMap { itemPsi ->
                paths.map { path ->
                    val qualifiedItem = QualifiedNamedItem2(itemPsi, path.path, path.crate)
                    val importInfo = qualifiedItem.toImportInfo(rootDefMap)
                    ImportCandidate2(qualifiedItem, importInfo)
                }
            }
        }
        .filter { it.qualifiedNamedItem.item !is RsTraitItem || isUsefulTraitImport(it.info.usePath) }
        // for items which belongs to multiple namespaces (e.g. unit structs)
        .distinctBy { it.qualifiedNamedItem.item to it.info.usePath }
        .sorted()


@Suppress("ArrayInDataClass")
private data class ModUsePath(
    val path: Array<String>,
    /** corresponds to `path.first()` */
    val crate: Crate,
    /** corresponds to `path.last()` */
    val mod: ModData
)

private fun ImportContext2.getAllModPaths(): List<ModUsePath> {
    val defMaps = rootDefMap.getInitialDefMapsToSearch(project.crateGraph)
    val explicitCrates = defMaps.explicit.mapToSet { (_, defMap) -> defMap.crate }
    val result = mutableListOf<ModUsePath>()
    for ((crateName, defMap) in defMaps.all) {
        val filterCrate = { crate: CratePersistentId -> crate == defMap.crate || crate !in explicitCrates }
        val crate = project.crateGraph.findCrateById(defMap.crate) ?: continue
        val rootPath = ModUsePath(arrayOf(crateName), crate, defMap.root)
        visitVisibleModules(rootPath, filterCrate, result::add)
    }
    return result
}

/** bfs using [ModData.visibleItems] as edges */
private fun ImportContext2.visitVisibleModules(
    rootPath: ModUsePath,
    filterCrate: (CratePersistentId) -> Boolean,
    processor: (ModUsePath) -> Unit
) {
    val visited = hashSetOf(rootPath.mod)
    var pathsCurrent = listOf(rootPath)
    var pathsNext = mutableListOf<ModUsePath>()
    while (pathsCurrent.isNotEmpty()) {
        for (pathCurrent in pathsCurrent) {
            processor(pathCurrent)
            for (pathNext in findPathsToModulesInScope(pathCurrent)) {
                if (filterCrate(pathNext.mod.crate) && pathNext.mod !in visited) {
                    pathsNext += pathNext
                }
            }
        }
        visited += pathsNext.map { it.mod }
        pathsCurrent = pathsNext
        pathsNext = mutableListOf()
    }
}

private fun ImportContext2.findPathsToModulesInScope(path: ModUsePath): List<ModUsePath> =
    path.mod.visibleItems.mapNotNull { (name, perNs) ->
        val childMod = perNs.types.singleOrNull {
            it.isModOrEnum && checkVisibility(it, path.mod)
        } ?: return@mapNotNull null
        val childModData = rootDefMap.tryCastToModData(childMod) ?: return@mapNotNull null
        ModUsePath(path.path + name, path.crate, childModData)
    }

private data class InitialDefMaps(
    /**
     * Crates which are available as-is (without inserting addition `extern crate`).
     * Note that though technically `core` is available as-is,
     * it is not included in this list since `std` should be used instead.
     */
    val explicit: List<Pair<String, CrateDefMap>>,
    /** All crates which we can import from */
    val all: List<Pair<String, CrateDefMap>>,
)

private fun CrateDefMap.getInitialDefMapsToSearch(crateGraph: CrateGraphService): InitialDefMaps {
    val externPreludeAdjusted = if (AutoInjectedCrates.STD in externPrelude) {
        externPrelude.filterKeys { it != AutoInjectedCrates.CORE }
    } else {
        externPrelude
    }
    val dependencies = externPreludeAdjusted
        .entries.groupBy({ it.value }, { it.key })
        .mapValues { (defMap, names) ->
            // if crate is imported using `extern crate` with alias, we should use alias
            // if there are multiply aliases, we choose any of them
            names.singleOrNull() ?: names.first { it != defMap.metaData.name }
        }
        .map { (defMap, name) -> name to defMap }
    // e.g. `alloc` and `proc_macro` crates
    val additionalStdlibDependencies = directDependenciesDefMaps
        .filter { (name, defMap) ->
            name !in externPreludeAdjusted
                && crateGraph.findCrateById(defMap.crate)?.origin == PackageOrigin.STDLIB
                && stdlibAttributes.canUseCrate(name)
        }
        .toList()
    val explicitDefMaps = listOf("crate" to this) + dependencies
    val allDefMaps = explicitDefMaps + additionalStdlibDependencies
    return InitialDefMaps(explicitDefMaps, allDefMaps)
}

private fun Attributes.canUseCrate(crate: String): Boolean =
    when (this) {
        Attributes.NONE -> true
        Attributes.NO_STD -> crate != AutoInjectedCrates.STD
        Attributes.NO_CORE -> crate != AutoInjectedCrates.STD && crate != AutoInjectedCrates.CORE
    }

/**
 * Checks that import is visible, and it is not private reexport.
 * We shouldn't use private reexports in order to not generate code like `use crate::HashSet;`.
 */
private fun ImportContext2.checkVisibility(visItem: VisItem, modData: ModData): Boolean {
    val visibility = visItem.visibility
    if (!visibility.isVisibleFromMod(rootModData)) return false
    if (visibility is Visibility.Restricted) {
        val isPrivate = visibility.inMod == modData && !visibility.inMod.isCrateRoot
        val isExplicitlyDeclared = !visItem.isCrateRoot && visItem.containingMod == modData.path
        if (isPrivate && !isExplicitlyDeclared) {
            Testmarks.ignorePrivateImportInParentMod.hit()
            return false
        }
    }
    return true
}


@Suppress("ArrayInDataClass")
private data class ItemUsePath(
    val path: Array<String>,
    /** corresponds to `path.first()` */
    val crate: Crate,
    /** corresponds to `path.last()` */
    val item: VisItem,
    val namespace: Namespace
) {
    fun toItemWithNamespace(): ItemWithNamespace = ItemWithNamespace(item.path, item.isModOrEnum, namespace)

    override fun toString(): String = "${path.joinToString("::")} for ${item.path}"
}

private fun ImportContext2.getAllItemPathsInMod(modPath: ModUsePath, itemNameFilter: (String) -> Boolean): Sequence<ItemUsePath> =
    modPath.mod.visibleItems
        .asSequence().filter { itemNameFilter(it.key) }
        .flatMap { (name, perNs) -> getPerNsPaths(modPath, perNs, name) }

private fun ImportContext2.getAllItemPathsInMod(modPath: ModUsePath, itemName: String): List<ItemUsePath> {
    val perNs = modPath.mod.visibleItems[itemName] ?: return emptyList()
    return getPerNsPaths(modPath, perNs, itemName)
}

private fun ImportContext2.getPerNsPaths(modPath: ModUsePath, perNs: PerNs, name: String): List<ItemUsePath> =
    perNs.getVisItemsByNamespace().flatMap { (visItems, namespace) ->
        visItems
            .filter { checkVisibility(it, modPath.mod) && !hasVisibleItemInRootScope(name, namespace) }
            .map { ItemUsePath(modPath.path + name, modPath.crate, it, namespace) }
    }

private fun ImportContext2.hasVisibleItemInRootScope(name: String, namespace: Namespace): Boolean {
    val perNs = rootDefMap.resolveNameInModule(rootModData, name, withLegacyMacros = true)
    return perNs.getVisItems(namespace).isNotEmpty()
}


private data class ItemWithNamespace(val path: ModPath, val isModOrEnum: Boolean, val namespace: Namespace) {
    override fun toString(): String = "$path ($namespace)"
}

private fun ItemWithNamespace.toPsi(defMap: CrateDefMap, project: Project): List<RsNamedElement> =
    VisItem(path, Visibility.Public, isModOrEnum).toPsi(defMap, project, namespace)

private fun filterForSingleItem(paths: List<ItemUsePath>, item: ItemWithNamespace): List<ItemUsePath> =
    filterForSingleCrate(paths, item)
        .groupBy { it.crate }
        .mapValues { filterShortestPath(it.value) }
        .flatMap { it.value }

/**
 * If we can access crate of item ⇒ ignore paths through other crates.
 * Exception: when item is declared in `core` and reexported in `std`, we should use `std` path.
 */
private fun filterForSingleCrate(paths: List<ItemUsePath>, item: ItemWithNamespace): List<ItemUsePath> {
    return paths.filter { it.crate.normName == AutoInjectedCrates.STD }
        .ifEmpty {
            paths.filter { it.crate.id == item.path.crate }
        }
        .ifEmpty {
            paths
        }
}

/** In each crate choose the shortest path(s) */
private fun filterShortestPath(paths: List<ItemUsePath>): List<ItemUsePath> {
    val minPathSize = paths.minOf { it.path.size }
    return paths.filter { it.path.size == minPathSize }
}

private fun ImportContext2.isUsefulTraitImport(usePath: String): Boolean {
    val path = RsCodeFragmentFactory(project).createPathInTmpMod(
        parentPathText ?: return true,
        rootMod,
        pathParsingMode,
        TYPES_N_VALUES,
        usePath,
        null
    ) ?: return false
    val element = path.reference?.deepResolve() as? RsQualifiedNamedElement ?: return false

    // Looks like it's useless to access trait associated types directly (i.e. `Trait::Type`),
    // but methods can be used in UFCS and associated functions or constants can be accessed
    // it they have `Self` type in a signature
    return element !is RsAbstractable
        || element.owner !is RsAbstractableOwner.Trait
        || element.canBeAccessedByTraitName
}

private fun QualifiedNamedItem2.toImportInfo(defMap: CrateDefMap): ImportInfo {
    val crateName = path.first()
    return if (crateName == "crate") {
        val usePath = path.joinToString("::").let {
            if (defMap.isAtLeastEdition2018) it else it.removePrefix("crate::")
        }
        ImportInfo.LocalImportInfo(usePath)
    } else {
        val needInsertExternCrateItem = !defMap.isAtLeastEdition2018 && !defMap.hasExternCrateInCrateRoot(crateName)
        val crateRelativePath = path.copyOfRange(1, path.size).joinToString("::")
        ImportInfo.ExternCrateImportInfo(containingCrate, crateName, needInsertExternCrateItem, null, crateRelativePath)
    }
}

private fun CrateDefMap.hasExternCrateInCrateRoot(externCrateName: String): Boolean {
    val externDefMap = externPrelude[externCrateName] ?: return false
    return externCratesInRoot[externCrateName] == externDefMap
}