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

plugins {
    id("com.android.library")
    id("project-convention-spotbugs")
    id("project-convention-logging")
    id("project-convention-spotless")
}

android {
    compileSdk = 37

    defaultConfig {
        minSdk = 19

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    namespace = "com.yubico.yubikit.android"
    lint {
        targetSdk = 37
    }
    testOptions {
        targetSdk = 37
    }
}

dependencies {
    api(project(":core"))

    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.24.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

description = "This module is the core library implementation and provides functionality to detect a YubiKey plugged in or tapped over NFC and to open an ISO/IEC 7816 connection that allows sending raw APDU commands to the key."

apply(from = project.file("publish.gradle"))
