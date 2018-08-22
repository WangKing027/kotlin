/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.build

import com.intellij.openapi.util.Key
import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.incremental.CompileContext
import org.jetbrains.jps.incremental.FSOperations
import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.jps.incremental.fs.CompilationRound
import org.jetbrains.jps.incremental.messages.CompilerMessage
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.kotlin.incremental.LookupSymbol
import org.jetbrains.kotlin.incremental.storage.version.CacheAttributesDiff
import org.jetbrains.kotlin.incremental.storage.version.CacheStatus
import org.jetbrains.kotlin.incremental.storage.version.loadDiff
import org.jetbrains.kotlin.jps.incremental.*
import org.jetbrains.kotlin.jps.platforms.KotlinModuleBuildTarget
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal val kotlinCompileContextKey = Key<KotlinCompileContext>("kotlin")

internal val CompileContext.kotlin: KotlinCompileContext
    get() = getUserData(kotlinCompileContextKey)
        ?: error(
            "KotlinCompilation available only at build phase " +
                    "(between first KotlinBuilder.chunkBuildStarted and KotlinBuilder.buildFinished)"
        )

class KotlinCompileContext(val jpsContext: CompileContext) {
    init {
        jpsContext.testingContext?.kotlinCompileContext = this
    }

    // TODO(1.2.80): As all targets now loaded at build start, no ConcurrentHasMap in KotlinBuildTargets needed anymore
    val targetsBinding = KotlinBuildTargets(jpsContext)

    val dataManager = jpsContext.projectDescriptor.dataManager
    val dataPaths = dataManager.dataPaths
    val testingLogger: TestingBuildLogger?
        get() = jpsContext.testingContext?.buildLogger

    lateinit var chunks: List<KotlinChunk>
    private lateinit var chunksBindingByRepresentativeTarget: Map<ModuleBuildTarget, KotlinChunk>
    lateinit var lookupsCacheAttributesManager: CompositeLookupsCacheAttributesManager
    lateinit var initialLookupsCacheStateDiff: CacheAttributesDiff<*>

    val shouldCheckCacheVersions = System.getProperty(KotlinBuilder.SKIP_CACHE_VERSION_CHECK_PROPERTY) == null

    val rebuildAfterCacheVersionChanged = RebuildAfterCacheVersionChangeMarker(dataManager)
    val hasKotlinMarker = HasKotlinMarker(dataManager)

    var rebuildingAllKotlin = false

    fun loadTargets() {
        val chunks = mutableListOf<KotlinChunk>()

        // visit all kotlin build targets
        jpsContext.projectDescriptor.buildTargetIndex.getSortedTargetChunks(jpsContext).forEach { chunk ->
            val moduleBuildTargets = chunk.targets.mapNotNull {
                if (it is ModuleBuildTarget) jpsContext.kotlinBuildTargets[it]!!
                else null
            }

            if (moduleBuildTargets.isNotEmpty()) {
                val kotlinChunk = KotlinChunk(this, moduleBuildTargets)
                chunks.add(kotlinChunk)
            }
        }

        this.chunks = chunks.toList()
        this.chunksBindingByRepresentativeTarget = chunks.associateBy { it.representativeTarget.jpsModuleBuildTarget }

        calculateChunkDependencies()
    }

    private fun calculateChunkDependencies() {
        val kotlinChunkByModule = mutableMapOf<JpsModule, KotlinChunk>()

        chunks.forEach { chunk ->
            chunk.targets.forEach { target ->
                kotlinChunkByModule[target.module] = chunk
            }
        }

        chunks.forEach { chunk ->
            val classpathKind = JpsJavaClasspathKind.compile(chunk.containsTests)
            val dependencies = mutableSetOf<KotlinChunk>()

            chunk.targets.forEach {
                JpsJavaExtensionService.dependencies(it.module)
                    .includedIn(classpathKind)
                    .recursivelyExportedOnly()
                    .processModules { module ->
                        val kotlinChunk = kotlinChunkByModule[module]
                        if (kotlinChunk != null) {
                            dependencies.add(kotlinChunk)
                        }
                    }
            }

            chunk.dependencies = dependencies.toList()
            chunk.dependencies.forEach { dependency ->
                dependency._dependentBuilder!!.add(chunk)
            }
        }

        chunks.forEach {
            it.dependent = it._dependentBuilder!!.toList()
            it._dependentBuilder = null
        }
    }

    fun loadLookupsCacheAttributes() {
        val expectedLookupsCacheComponents = mutableSetOf<String>()
        chunks.forEach { chunk ->
            chunk.targets.forEach { target ->
                if (target.isIncrementalCompilationEnabled) {
                    expectedLookupsCacheComponents.add(target.globalLookupCacheId)
                }
            }
        }

        val lookupsCacheRootPath = dataPaths.getTargetDataRoot(KotlinDataContainerTarget)
        this.lookupsCacheAttributesManager = CompositeLookupsCacheAttributesManager(lookupsCacheRootPath, expectedLookupsCacheComponents)
        this.initialLookupsCacheStateDiff = lookupsCacheAttributesManager.loadDiff()

        if (initialLookupsCacheStateDiff.status == CacheStatus.VALID) {
            // try to perform a lookup
            // request rebuild if storage is corrupted
            try {
                dataManager.withLookupStorage {
                    it.get(LookupSymbol("<#NAME#>", "<#SCOPE#>"))
                }
            } catch (e: Exception) {
                val error = CompilerMessage.createInternalBuilderError("Kotlin", Exception("Lookups probe failed: ", e))
                jpsContext.processMessage(error)

                jpsContext.kotlin.markAllKotlinForRebuild("Lookup storage is corrupted")
                initialLookupsCacheStateDiff = initialLookupsCacheStateDiff.copy(actual = null)
            }
        }
    }

    fun checkCacheVersions() {
        when (initialLookupsCacheStateDiff.status) {
            CacheStatus.INVALID -> {
                // global cache needs to be rebuilt

                testingLogger?.invalidOrUnusedCache(null, null, initialLookupsCacheStateDiff)

                if (initialLookupsCacheStateDiff.actual != null) {
                    markAllKotlinForRebuild("Kotlin incremental cache settings or format was changed")
                    clearLookupCache()
                } else {
                    markAllKotlinForRebuild("Kotlin incremental cache is missed or corrupted")
                }
            }
            CacheStatus.VALID -> Unit
            CacheStatus.SHOULD_BE_CLEARED -> {
                jpsContext.testingContext?.buildLogger?.invalidOrUnusedCache(null, null, initialLookupsCacheStateDiff)
                KotlinBuilder.LOG.info("Removing global cache as it is not required anymore: $initialLookupsCacheStateDiff")

                clearAllCaches()
            }
            CacheStatus.CLEARED -> Unit
        }
    }

    private val lookupAttributesSaved = AtomicBoolean(false)

    /**
     * Called on every successful compilation
     */
    fun ensureLookupsCacheAttributesSaved() {
        if (lookupAttributesSaved.compareAndSet(false, true)) {
            initialLookupsCacheStateDiff.saveExpectedIfNeeded()
        }
    }

    fun checkChunkCacheVersion(chunk: KotlinChunk) {
        if (shouldCheckCacheVersions && !rebuildingAllKotlin) {
            if (chunk.shouldRebuild()) markChunkForRebuildBeforeBuild(chunk)
        }
    }

    private fun logMarkDirtyForTestingBeforeRound(file: File, shouldProcess: Boolean): Boolean {
        if (shouldProcess) {
            testingLogger?.markedAsDirtyBeforeRound(listOf(file))
        }
        return shouldProcess
    }

    fun markAllKotlinForRebuild(reason: String) {
        if (rebuildingAllKotlin) return
        rebuildingAllKotlin = true

        KotlinBuilder.LOG.info("Rebuilding all Kotlin: $reason")

        val dataManager = jpsContext.projectDescriptor.dataManager

        chunks.forEach {
            markChunkForRebuildBeforeBuild(it)
        }

        dataManager.cleanLookupStorage(KotlinBuilder.LOG)
    }

    private fun markChunkForRebuildBeforeBuild(chunk: KotlinChunk) {
        chunk.targets.forEach {
            FSOperations.markDirty(jpsContext, CompilationRound.NEXT, it.jpsModuleBuildTarget) { file ->
                logMarkDirtyForTestingBeforeRound(file, file.isKotlinSourceFile)
            }

            dataManager.getKotlinCache(it)?.clean()
            hasKotlinMarker.clean(it.jpsModuleBuildTarget)
            rebuildAfterCacheVersionChanged[it.jpsModuleBuildTarget] = true
        }
    }

    private fun clearAllCaches() {
        clearLookupCache()

        KotlinBuilder.LOG.info("Clearing caches for all targets")
        chunks.forEach { chunk ->
            chunk.targets.forEach {
                dataManager.getKotlinCache(it)?.clean()
            }
        }
    }

    private fun clearLookupCache() {
        KotlinBuilder.LOG.info("Clearing lookup cache")
        dataManager.cleanLookupStorage(KotlinBuilder.LOG)
        initialLookupsCacheStateDiff.saveExpectedIfNeeded()
    }

    fun cleanupCaches() {
        // todo: remove lookups for targets with disabled IC (or split global lookups cache into several caches for each compiler)

        chunks.forEach { chunk ->
            chunk.targets.forEach { target ->
                if (target.initialLocalCacheAttributesDiff.status == CacheStatus.SHOULD_BE_CLEARED) {
                    KotlinBuilder.LOG.info(
                        "$target caches is cleared as not required anymore: ${target.initialLocalCacheAttributesDiff}"
                    )
                    testingLogger?.invalidOrUnusedCache(null, target, target.initialLocalCacheAttributesDiff)
                    dataManager.getKotlinCache(target)?.clean()
                }
            }
        }
    }

    fun dispose() {

    }

    fun getChunk(rawChunk: ModuleChunk): KotlinChunk? {
        val rawRepresentativeTarget = rawChunk.representativeTarget()
        if (targetsBinding[rawRepresentativeTarget] == null) return null

        return chunksBindingByRepresentativeTarget[rawRepresentativeTarget]
            ?: error("Kotlin binding for chunk $this is not loaded at build start")
    }
}

