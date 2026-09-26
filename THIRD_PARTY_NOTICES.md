# Third-party notices — SMS Tech

SMS Tech (`com.filestech.sms`) is licensed under the Apache License 2.0 ([LICENSE](LICENSE)). It
ships the open-source libraries listed below. The list was read on 2026-09-26 from the resolved
`releaseRuntimeClasspath` of the `:app` module at `v1.28.12`, and each licence from the POM that
the library publishes — not from memory.

**No Google Play Services, no ML Kit, no Firebase, no analytics SDK, no crash reporter.**

## Shipped in the APK

| Library | Version | Publisher | Licence |
|---|---|---|---|
| AndroidX: Core, Activity, Lifecycle, Navigation, SavedState, AppCompat, Emoji2, Fragment, Window, Startup, Tracing, Profile Installer, DocumentFile, Biometric and their support modules | various | Google / AOSP | Apache 2.0 |
| Jetpack Compose: UI, Foundation, Animation, Runtime, Material 3, Material Icons | BOM 2026.09.00 (UI 1.12.1, Material 3 1.4.0) | Google / AOSP | Apache 2.0 |
| Room (`androidx.room`) and `androidx.sqlite` | 2.8.5 / 2.7.0 | Google / AOSP | Apache 2.0 |
| DataStore Preferences | 1.2.1 | Google / AOSP | Apache 2.0 — its embedded copy of Protocol Buffers is BSD-3-Clause, see below |
| WorkManager | 2.11.2 | Google / AOSP | Apache 2.0 |
| Hilt and Dagger (`com.google.dagger`, `androidx.hilt`) | 2.60.1 / 1.4.0 | Google | Apache 2.0 |
| Accompanist Permissions | 0.37.3 | Google | Apache 2.0 |
| Guava `listenablefuture`, JSR-305 annotations | 1.0 / 3.0.2 | Google | Apache 2.0 |
| Kotlin standard library | 2.4.10 | JetBrains | Apache 2.0 |
| kotlinx.coroutines, kotlinx.serialization | 1.11.0 / 1.11.0 | JetBrains | Apache 2.0 |
| JetBrains annotations, JSpecify | 23.0.0 / 1.0.0 | JetBrains / JSpecify | Apache 2.0 |
| `javax.inject`, `jakarta.inject-api` | 1 / 2.0.1 | JSR-330 / Eclipse Foundation | Apache 2.0 |
| Coil (image loading) | 2.7.0 | Coil contributors | Apache 2.0 |
| OkHttp, Okio (pulled in by Coil) | 4.12.0 / 3.9.1 | Square | Apache 2.0 |
| Timber | 5.0.1 | Jake Wharton | Apache 2.0 |
| **SQLCipher for Android** | 4.19.0 | Zetetic LLC | **BSD-3-Clause** — full text below |

OkHttp is present because Coil depends on it. SMS Tech uses Coil to display contact photos and
attachments stored on the phone; its one network use is MMS transport through your carrier (see
[PRIVACY.md](PRIVACY.md)).

SQLCipher bundles **SQLite** and **LibTomCrypt**, both released into the public domain by their
authors.

## Licence texts

### Apache License 2.0

Every library marked "Apache 2.0" above is distributed under the same text as SMS Tech itself:
[LICENSE](LICENSE), also at <https://www.apache.org/licenses/LICENSE-2.0>. Of all these libraries,
only `jakarta.inject-api` ships a `NOTICE` file; its attribution is reproduced below.

### Eclipse Jakarta Dependency Injection — NOTICE (Apache 2.0)

> This content is produced and maintained by the Eclipse Jakarta Dependency Injection project.
> Project home: https://projects.eclipse.org/projects/cdi.batch
>
> Jakarta Dependency Injection is a trademark of the Eclipse Foundation.
>
> All content is the property of the respective authors or their employers. For more information
> regarding authorship of content, please consult the listed source code repository logs.
>
> Source code: https://github.com/eclipse-ee4j/injection-api

### SQLCipher for Android — BSD-3-Clause

```
Copyright (c) 2008-2023, ZETETIC LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Protocol Buffers (embedded in DataStore) — BSD-3-Clause

```
Copyright 2008 Google Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Build and test tools (not shipped in the APK)

Gradle, the Android Gradle Plugin, KSP, the Room and Hilt compilers, R8, detekt, ktlint, JUnit,
Robolectric, MockK, Turbine and the AndroidX test libraries are used to build and test SMS Tech.
They are not part of the APK.
