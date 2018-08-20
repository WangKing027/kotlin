import org.jetbrains.kotlin.gradle.dsl.Coroutines

plugins {
    kotlin("jvm")
}

dependencies {
    compile(project(":examples:scripting-jvm-simple-script"))
    compile(project(":kotlin-scripting-jvm-host"))
    compile(project(":kotlin-script-util"))
    runtime(project(":kotlin-compiler"))
    runtime(project(":kotlin-reflect"))
    testCompile(commonDep("junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

kotlin {
    experimental.coroutines = Coroutines.ENABLE
}
