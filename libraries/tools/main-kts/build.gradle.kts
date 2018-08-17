import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import proguard.gradle.ProGuardTask

description = "Kotlin \"main\" script definition"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

// You can run Gradle with "-Pkotlin.build.proguard=true" to enable ProGuard run on the jar (on TeamCity, ProGuard always runs)
val shrink =
    findProperty("kotlin.build.proguard")?.toString()?.toBoolean()
        ?: hasProperty("teamcity")

val jarBaseName = property("archivesBaseName") as String

val fatJarContents by configurations.creating
val proguardLibraryJars by configurations.creating
val fatJar by configurations.creating

val projectsDependencies = listOf(
    ":kotlin-scripting-common",
    ":kotlin-scripting-jvm",
    ":kotlin-script-util",
    ":kotlin-script-runtime")

dependencies {
    projectsDependencies.forEach {
        compileOnly(project(it))
        fatJarContents(project(it)) { isTransitive = false }
        testCompile(project(it))
    }
    fatJarContents("org.apache.ivy:ivy:2.4.0")
    fatJarContents(commonDep("org.jetbrains.kotlinx", "kotlinx-coroutines-core")) { isTransitive = false }
    proguardLibraryJars(files(firstFromJavaHomeThatExists("jre/lib/rt.jar", "../Classes/classes.jar"),
                              firstFromJavaHomeThatExists("jre/lib/jsse.jar", "../Classes/jsse.jar"),
                              toolsJar()))
    proguardLibraryJars(project(":kotlin-stdlib"))
    proguardLibraryJars(project(":kotlin-reflect"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

projectTest {
    workingDir = rootDir
}

val jar by tasks

val packJar by task<ShadowJar> {
    configurations = listOf(fatJar)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    destinationDir = File(buildDir, "libs")

    setupPublicJar("before-proguard")
    from(jar)
    from(fatJarContents)
}

val proguard by task<ProGuardTask> {
    dependsOn(packJar)
    configuration("main-kts.pro")

    injars(mapOf("filter" to "!META-INF/versions/**"), packJar.outputs.files)

    val outputJar = fileFrom(buildDir, "libs", "$jarBaseName-after-proguard.jar")

    outjars(outputJar)

    inputs.files(packJar.outputs.files.singleFile)
    outputs.file(outputJar)

    libraryjars(mapOf("filter" to "!META-INF/versions/**"), proguardLibraryJars)
    printconfiguration("$buildDir/compiler.pro.dump")
}

val pack = if (shrink) proguard else packJar

runtimeJarArtifactBy(pack, pack.outputs.files.singleFile) {
    name = jarBaseName
    classifier = ""
}
sourcesJar()
javadocJar()

publish()

