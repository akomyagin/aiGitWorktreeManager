import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "dev.alkom.gwm"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Terminal UI toolkit (tables, colored output, interactive selection lists).
    // Pure-JVM, lightweight — no native runtime, fast startup for everyday CLI use.
    implementation("com.github.ajalt.mordant:mordant:3.0.1")
    // Command-line argument parsing (subcommands, options, help).
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.alkom.gwm.MainKt")
    applicationName = "gwm"
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

// Make `./gradlew run` usable interactively (Mordant reads the real terminal).
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
