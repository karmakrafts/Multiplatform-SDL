/*
 * Copyright 2024 Karma Krafts & associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import de.undercouch.gradle.tasks.download.Download
import org.gradle.internal.extensions.stdlib.capitalized
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.downloadTask)
    `maven-publish`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

operator fun DirectoryProperty.div(name: String): Path = get().asFile.toPath() / name

val ensureBuildDirectory: Task = tasks.create("ensureBuildDirectory") {
    val path = layout.buildDirectory.get().asFile.toPath()
    doLast { path.createDirectories() }
    onlyIf { path.notExists() }
}

fun downloadSdlBinariesTask(platform: String, arch: String): Download =
    tasks.create<Download>("downloadSdlBinaries${platform.capitalized()}${arch.capitalized()}") {
        group = "sdlBinaries"
        dependsOn(ensureBuildDirectory)
        val fileName = "build-$platform-$arch-debug.zip"
        src("https://git.karmakrafts.dev/api/v4/projects/338/packages/generic/build/${libs.versions.sdl.get()}/$fileName")
        val destPath = layout.buildDirectory / "sdl" / fileName
        dest(destPath.toFile())
        overwrite(true) // Always overwrite when downloading binaries
        onlyIf { destPath.notExists() }
    }

val downloadSdlBinariesWindowsX64: Download = downloadSdlBinariesTask("windows", "x64")
val downloadSdlBinariesLinuxX64: Download = downloadSdlBinariesTask("linux", "x64")
val downloadSdlBinariesLinuxArm64: Download = downloadSdlBinariesTask("linux", "arm64")
val downloadSdlBinariesMacosX64: Download = downloadSdlBinariesTask("macos", "x64")
val downloadSdlBinariesMacosArm64: Download = downloadSdlBinariesTask("macos", "arm64")

fun extractSdlBinariesTask(platform: String, arch: String): Copy =
    tasks.create<Copy>("extractSdlBinaries${platform.capitalized()}${arch.capitalized()}") {
        group = "sdlBinaries"
        val downloadTaskName = "downloadSdlBinaries${platform.capitalized()}${arch.capitalized()}"
        dependsOn(downloadTaskName)
        val platformPair = "$platform-$arch"
        from(zipTree((layout.buildDirectory / "sdl" / "build-$platformPair-debug.zip").toFile()))
        val destPath = layout.buildDirectory / "sdl" / platformPair
        into(destPath.toFile())
        onlyIf { destPath.notExists() }
    }

val extractSdlBinariesWindowsX64: Copy = extractSdlBinariesTask("windows", "x64")
val extractSdlBinariesLinuxX64: Copy = extractSdlBinariesTask("linux", "x64")
val extractSdlBinariesLinuxArm64: Copy = extractSdlBinariesTask("linux", "arm64")
val extractSdlBinariesMacosX64: Copy = extractSdlBinariesTask("macos", "x64")
val extractSdlBinariesMacosArm64: Copy = extractSdlBinariesTask("macos", "arm64")

val extractSdlBinaries: Task = tasks.create("extractSdlBinaries") {
    group = "sdlBinaries"
    dependsOn(extractSdlBinariesWindowsX64)
    dependsOn(extractSdlBinariesLinuxX64)
    dependsOn(extractSdlBinariesLinuxArm64)
    dependsOn(extractSdlBinariesMacosX64)
    dependsOn(extractSdlBinariesMacosArm64)
}

val downloadSdlHeaders: Exec = tasks.create<Exec>("downloadSdlHeaders") {
    group = "sdlHeaders"
    dependsOn(ensureBuildDirectory)
    workingDir = layout.buildDirectory.get().asFile
    commandLine(
        "git", "clone", "--branch", libs.versions.sdl.get(), "--single-branch", "https://github.com/libsdl-org/SDL",
        "sdl/headers"
    )
    onlyIf { (layout.buildDirectory / "sdl" / "headers").notExists() }
}

val updateSdlHeaders: Exec = tasks.create<Exec>("updateSdlHeaders") {
    group = "sdlHeaders"
    dependsOn(downloadSdlHeaders)
    workingDir = (layout.buildDirectory / "sdl" / "headers").toFile()
    commandLine("git", "pull", "--force")
    onlyIf { (layout.buildDirectory / "sdl" / "headers").exists() }
}

kotlin {
    listOf(
        mingwX64(), linuxX64(), linuxArm64(), macosX64(), macosArm64()
    ).forEach {
        it.compilations.getByName("main") {
            cinterops {
                val sdl by creating {
                    tasks.getByName(interopProcessingTaskName) {
                        dependsOn(updateSdlHeaders)
                        dependsOn(extractSdlBinaries)
                    }
                }
            }
        }
    }
    applyDefaultHierarchyTemplate()
}

val dokkaJar by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaHtml)
    from(tasks.dokkaHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

tasks {
    dokkaHtml {
        dokkaSourceSets.create("main") {
            reportUndocumented = false
            jdkVersion = java.toolchain.languageVersion.get().asInt()
            noAndroidSdkLink = true
            externalDocumentationLink("https://docs.karmakrafts.dev/${rootProject.name}")
        }
    }
    System.getProperty("publishDocs.root")?.let { docsDir ->
        create<Copy>("publishDocs") {
            mustRunAfter(dokkaJar)
            from(zipTree(dokkaJar.get().outputs.files.first()))
            into(docsDir)
        }
    }
}

publishing {
    System.getenv("CI_API_V4_URL")?.let { apiUrl ->
        repositories {
            maven {
                url = uri("$apiUrl/projects/${System.getenv("CI_PROJECT_ID")}/packages/maven")
                name = "GitLab"
                credentials(HttpHeaderCredentials::class) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN")
                }
                authentication {
                    create("header", HttpHeaderAuthentication::class)
                }
            }
        }
    }
    publications.configureEach {
        if (this is MavenPublication) {
            artifact(dokkaJar)
            pom {
                name = project.name
                description = "Multiplatform bindings for SDL3 on Linux, Windows and macOS."
                url = System.getenv("CI_PROJECT_URL")
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "kitsunealex"
                        name = "KitsuneAlex"
                        url = "https://git.karmakrafts.dev/KitsuneAlex"
                    }
                }
                scm {
                    url = this@pom.url
                }
            }
        }
    }
}