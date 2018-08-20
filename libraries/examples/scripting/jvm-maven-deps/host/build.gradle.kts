import org.jetbrains.kotlin.gradle.dsl.Coroutines

plugins {
    kotlin("jvm")
}

dependencies {
    compile(project(":examples:scripting-jvm-maven-deps"))
    compile(project(":kotlin-scripting-jvm-host"))
    compile(project(":kotlin-stdlib"))
    compile(project(":kotlin-reflect"))
    compileOnly(project(":compiler:util"))
    runtime(projectRuntimeJar(":kotlin-compiler"))
    testCompile(commonDep("junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

kotlin {
    experimental.coroutines = Coroutines.ENABLE
}
