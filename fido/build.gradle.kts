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
    id("yubikit-java-library")
}

dependencies {
    api(project(":core"))

    testImplementation("org.mockito:mockito-core:5.24.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
}

extra["pomName"] = "Yubico YubiKit Fido"
description = "This module provides FIDO support for the YubiKit SDK."

apply(from = rootProject.file("publish.gradle"))
