/*
 * Copyright (C) 2026 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

plugins {
    id("com.android.application")
    id("project-convention-spotbugs")
    id("project-convention-logging")
}

android {
    namespace = "acab.naiveha.subrosa"
    compileSdk = 37

    packaging {
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
    }

    defaultConfig {
        applicationId = "acab.naiveha.subrosa"
        minSdk = 31
        targetSdk = 37
        versionCode = 15
        versionName = "2.3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("distribution", "apk")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("apk") {
            dimension = "distribution"
            applicationId = "acab.naiveha.subrosa"
        }
        create("aab") {
            dimension = "distribution"
            applicationId = "acab.naiveha.subrosa.by.naiveha"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

abstract class CopyAndRenameBundleTask : DefaultTask() {
    @get:Internal
    abstract val inputDir: DirectoryProperty

    @get:OutputFile
    abstract val outputReleaseFile: RegularFileProperty

    @get:OutputFile
    abstract val outputBuildFile: RegularFileProperty

    @get:Input
    abstract val targetFileName: Property<String>

    @TaskAction
    fun execute() {
        val inDir = inputDir.orNull?.asFile
        if (inDir != null && inDir.exists()) {
            val bundleFile = inDir.walkTopDown().firstOrNull { it.isFile && it.extension == "aab" && it.name != targetFileName.get() }
            if (bundleFile != null) {
                outputReleaseFile.get().asFile.parentFile.mkdirs()
                outputBuildFile.get().asFile.parentFile.mkdirs()
                bundleFile.copyTo(outputReleaseFile.get().asFile, overwrite = true)
                bundleFile.copyTo(outputBuildFile.get().asFile, overwrite = true)
            }
        }
    }
}

abstract class CopyAndRenameApkTask : DefaultTask() {
    @get:Internal
    abstract val inputDir: DirectoryProperty

    @get:OutputFile
    abstract val outputReleaseFile: RegularFileProperty

    @get:OutputFile
    abstract val outputBuildFile: RegularFileProperty

    @get:Input
    abstract val targetFileName: Property<String>

    @TaskAction
    fun execute() {
        val inDir = inputDir.orNull?.asFile
        if (inDir != null && inDir.exists()) {
            val apkFile = inDir.walkTopDown().firstOrNull { it.isFile && it.extension == "apk" && it.name != targetFileName.get() }
            if (apkFile != null) {
                outputReleaseFile.get().asFile.parentFile.mkdirs()
                outputBuildFile.get().asFile.parentFile.mkdirs()
                apkFile.copyTo(outputReleaseFile.get().asFile, overwrite = true)
                apkFile.copyTo(outputBuildFile.get().asFile, overwrite = true)
            }
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug").withFlavor("distribution" to "aab")) { variant ->
        variant.enable = false
    }

    onVariants { variant ->
        val apkVersionName = "2.3.3"
        val isAab = variant.productFlavors.any { it.second == "aab" } || variant.name.contains("aab", ignoreCase = true)
        val ext = if (isAab) "aab" else "apk"
        val targetName = "subrosa.$apkVersionName.$ext"
        val releaseDir = layout.projectDirectory.dir("../release")

        if (isAab) {
            val listingTaskName = "produce${variant.name.replaceFirstChar { it.uppercase() }}BundleIdeListingFile"
            val copyAndRenameTask = tasks.register<CopyAndRenameBundleTask>("copyAndRename${variant.name}Bundle") {
                inputDir.set(layout.buildDirectory.dir("outputs/bundle/${variant.name}"))
                outputReleaseFile.set(releaseDir.file(targetName))
                outputBuildFile.set(layout.buildDirectory.file("outputs/bundle/${variant.name}/$targetName"))
                targetFileName.set(targetName)
            }
            tasks.matching { it.name == listingTaskName }.configureEach {
                finalizedBy(copyAndRenameTask)
            }
        } else {
            val assembleTaskName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
            val flavorName = variant.productFlavors.firstOrNull()?.second ?: "apk"
            val buildTypeName = variant.buildType
            val listingTaskName = "create${variant.name.replaceFirstChar { it.uppercase() }}ApkListingFileRedirect"

            val copyAndRenameTask = tasks.register<CopyAndRenameApkTask>("copyAndRename${variant.name}Apk") {
                inputDir.set(layout.buildDirectory.dir("outputs/apk/$flavorName/$buildTypeName"))
                outputReleaseFile.set(releaseDir.file(targetName))
                outputBuildFile.set(layout.buildDirectory.file("outputs/apk/$flavorName/$buildTypeName/$targetName"))
                targetFileName.set(targetName)
            }
            tasks.matching { it.name == assembleTaskName }.configureEach {
                finalizedBy(copyAndRenameTask)
            }
            tasks.matching { it.name == listingTaskName }.configureEach {
                mustRunAfter(copyAndRenameTask)
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(project(":android"))
    implementation(project(":management"))
    implementation(project(":fido"))
    implementation(project(":yubiotp"))
    implementation(project(":openpgp"))
    implementation(project(":support"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.14.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.10.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.10.2")

    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("org.bouncycastle:bcpg-jdk18on:1.86")

    implementation("com.github.tony19:logback-android:3.0.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
