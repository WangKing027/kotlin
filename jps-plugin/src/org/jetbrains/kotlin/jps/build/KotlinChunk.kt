/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.build

import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.incremental.storage.version.CacheStatus
import org.jetbrains.kotlin.jps.incremental.JpsIncrementalCache
import org.jetbrains.kotlin.jps.incremental.getKotlinCache
import org.jetbrains.kotlin.jps.model.kotlinCompilerArguments
import org.jetbrains.kotlin.jps.platforms.KotlinModuleBuildTarget
import org.jetbrains.kotlin.utils.keysToMapExceptNulls
import java.io.File

class KotlinChunk(val context: KotlinCompileContext, val targets: List<KotlinModuleBuildTarget<*>>) {
    val containsTests = targets.any { it.isTests }

    lateinit var dependencies: List<KotlinChunk>

    internal var _dependentBuilder: MutableSet<KotlinChunk>? = mutableSetOf()
    lateinit var dependent: List<KotlinChunk>

    fun collectDependentChunksRecursively(result: MutableSet<KotlinChunk> = mutableSetOf()) {
        dependent.forEach {
            if (result.add(it)) {
                it.collectDependentChunksRecursively(result)
            }
        }
    }

    val representativeTarget
        get() = targets.first()

    private val presentableModulesToCompilersList: String
        get() = targets.joinToString { "${it.module.name} (${it.globalLookupCacheId})" }

    init {
        check(targets.all { it.javaClass == representativeTarget.javaClass }) {
            "Cyclically dependent modules $presentableModulesToCompilersList should have same compiler."
        }
    }

    val compilerArguments = representativeTarget.jpsModuleBuildTarget.module.kotlinCompilerArguments

    val langVersion =
        compilerArguments.languageVersion?.let { LanguageVersion.fromVersionString(it) } ?: LanguageVersion.LATEST_STABLE

    val apiVersion =
        compilerArguments.apiVersion?.let { ApiVersion.parse(it) } ?: ApiVersion.createByLanguageVersion(
            langVersion
        )

    fun shouldRebuild(): Boolean {
        if (isVersionChanged()) {
            // info message about version change reported in `isVersionChanged()`
            return true
        }

        targets.forEach {
            if (it.initialLocalCacheAttributesDiff.status == CacheStatus.INVALID) {
                context.testingLogger?.invalidOrUnusedCache(this, null, it.initialLocalCacheAttributesDiff)
                KotlinBuilder.LOG.info("$it cache is invalid ${it.initialLocalCacheAttributesDiff}, rebuilding $this")
                return true
            }
        }

        return false
    }

    private fun isVersionChanged(): Boolean {
        val buildMetaInfo = representativeTarget.buildMetaInfoFactory.create(compilerArguments)

        for (target in targets) {
            if (target.isVersionChanged(this, buildMetaInfo)) {
                KotlinBuilder.LOG.info("$target version changed, rebuilding $this")
                return true
            }
        }

        return false
    }

    fun buildMetaInfoFile(target: ModuleBuildTarget): File =
        File(
            context.dataPaths.getTargetDataRoot(target),
            representativeTarget.buildMetaInfoFileName
        )

    fun saveVersions() {
        context.ensureLookupsCacheAttributesSaved()

        targets.forEach {
            it.initialLocalCacheAttributesDiff.saveExpectedIfNeeded()
        }

        val serializedMetaInfo = representativeTarget.buildMetaInfoFactory.serializeToString(compilerArguments)

        targets.forEach {
            buildMetaInfoFile(it.jpsModuleBuildTarget).writeText(serializedMetaInfo)
        }
    }

    fun loadCaches(loadDependent: Boolean = true): Map<KotlinModuleBuildTarget<*>, JpsIncrementalCache> {
        val dataManager = context.dataManager

        val cacheByChunkTarget = targets.keysToMapExceptNulls {
            dataManager.getKotlinCache(it)
        }

        if (loadDependent) {
            addDependentCaches(cacheByChunkTarget.values)
        }

        return cacheByChunkTarget
    }

    private fun addDependentCaches(targetsCaches: Collection<JpsIncrementalCache>) {
        val dependentChunks = mutableSetOf<KotlinChunk>()
        collectDependentChunksRecursively(dependentChunks)

        val dataManager = context.dataManager
        dependentChunks.forEach { decedentChunk ->
            decedentChunk.targets.forEach {
                val dependentCache = dataManager.getKotlinCache(it)
                if (dependentCache != null) {

                    for (chunkCache in targetsCaches) {
                        chunkCache.addJpsDependentCache(dependentCache)
                    }
                }
            }
        }
    }

    /**
     * The same as [org.jetbrains.jps.ModuleChunk.getPresentableShortName]
     */
    val presentableShortName: String
        get() = buildString {
            if (containsTests) append("tests of ")
            append(targets.first().module.name)
            if (targets.size > 1) {
                val andXMore = "and ${targets.size - 1} more"
                val other = ", " + targets.asSequence().drop(1).joinToString()
                append(if (other.length < andXMore.length) other else andXMore)
            }
        }

    override fun toString(): String {
        return "KotlinChunk<${representativeTarget.javaClass.simpleName}>" +
                "(${targets.joinToString { it.jpsModuleBuildTarget.presentableName }})"
    }
}