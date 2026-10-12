import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "caloo"
version = "1.0.0"

dependencies {
    // Même API JSON que sur Android (org.json), un seul petit jar.
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("caloo.MainKt")
    applicationName = "caloo-server"
}

tasks.test { useJUnitPlatform() }
