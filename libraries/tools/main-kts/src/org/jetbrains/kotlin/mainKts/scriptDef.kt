/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.mainKts

import org.jetbrains.kotlin.script.util.DependsOn
import org.jetbrains.kotlin.script.util.FilesAndIvyResolver
import org.jetbrains.kotlin.script.util.Repository
import org.jetbrains.kotlin.script.util.scriptCompilationClasspathFromContext
import java.io.File
import kotlin.script.dependencies.ScriptContents
import kotlin.script.dependencies.ScriptDependenciesResolver
import kotlin.script.experimental.annotations.*
import kotlin.script.experimental.api.*
import kotlin.script.experimental.basic.AnnotationsBasedCompilationConfigurator
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.jvmJavaHomeParams
import kotlin.script.experimental.jvm.mapLegacyDiagnosticSeverity
import kotlin.script.experimental.jvm.mapLegacyScriptPosition
import kotlin.script.experimental.jvm.runners.BasicJvmScriptEvaluator
import kotlin.script.experimental.misc.invoke
import kotlin.script.experimental.util.TypedKey

@Suppress("unused")
@KotlinScript
@KotlinScriptFileExtension("main.kts")
@KotlinScriptDefaultCompilationConfiguration(DefaultMainKtsConfiguration::class)
@KotlinScriptCompilationConfigurator(MainKtsConfigurator::class)
@KotlinScriptEvaluator(BasicJvmScriptEvaluator::class)
abstract class MainKtsScript(val args: Array<String>)

private val defaultMainKtsConfigParams = jvmJavaHomeParams + with(ScriptCompileConfigurationProperties) {
    listOf(
        baseClass<MainKtsScript>(),
        defaultImports(DependsOn::class.java.name, Repository::class.java.name),
        dependencies(
            JvmDependency(
                scriptCompilationClasspathFromContext(
                    "scripting-jvm-maven-deps", // script library jar name
                    "kotlin-script-util" // DependsOn annotation is taken from script-util
                )
            )
        ),
        refineConfigurationOnAnnotations(DependsOn::class, Repository::class)
    )
}

object DefaultMainKtsConfiguration : List<Pair<TypedKey<*>, Any?>> by defaultMainKtsConfigParams

class MainKtsConfigurator(environment: ScriptingEnvironment) : AnnotationsBasedCompilationConfigurator(environment) {

    private val resolver = FilesAndIvyResolver()

    override suspend fun refineConfiguration(
        scriptSource: ScriptSource,
        configuration: ScriptCompileConfiguration,
        processedScriptData: ProcessedScriptData
    ): ResultWithDiagnostics<ScriptCompileConfiguration> {
        val annotations = processedScriptData.getOrNull(ProcessedScriptDataProperties.foundAnnotations)?.takeIf { it.isNotEmpty() }
            ?: return configuration.asSuccess()
        val scriptContents = object : ScriptContents {
            override val annotations: Iterable<Annotation> = annotations
            override val file: File? = null
            override val text: CharSequence? = null
        }
        val diagnostics = arrayListOf<ScriptDiagnostic>()
        fun report(severity: ScriptDependenciesResolver.ReportSeverity, message: String, position: ScriptContents.Position?) {
            diagnostics.add(ScriptDiagnostic(message, mapLegacyDiagnosticSeverity(severity), mapLegacyScriptPosition(position)))
        }
        return try {
            val newDepsFromResolver = resolver.resolve(scriptContents, emptyMap(), ::report, null).get()
                ?: return configuration.asSuccess(diagnostics)
            val resolvedClasspath = newDepsFromResolver.classpath.toList().takeIf { it.isNotEmpty() }
                ?: return configuration.asSuccess(diagnostics)
            val newDependency = JvmDependency(resolvedClasspath)
            val updatedDeps =
                configuration.getOrNull(ScriptCompileConfigurationProperties.dependencies)?.plus(newDependency) ?: listOf(newDependency)
            ScriptCompileConfiguration(configuration, ScriptCompileConfigurationProperties.dependencies(updatedDeps)).asSuccess(diagnostics)
        } catch (e: Throwable) {
            ResultWithDiagnostics.Failure(*diagnostics.toTypedArray(), e.asDiagnostics())
        }
    }
}

