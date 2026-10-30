# Third-party notices

Wayfinder's APK includes the libraries below. All of them allow redistribution in source and binary form, as long as the notices are kept. Test-only dependencies are not shipped in the APK and are not listed.

| Library | Coordinates | License |
|---|---|---|
| AndroidX (Core, Activity, Lifecycle, Compose UI/Foundation/Material 3, Material Icons Extended) | `androidx.*` | Apache-2.0 |
| Kotlin standard library & kotlinx.coroutines | `org.jetbrains.kotlin*`, `org.jetbrains.kotlinx*` | Apache-2.0 |
| Coil 3 (compose, video) | `io.coil-kt.coil3:*` | Apache-2.0 |
| SMBJ | `com.hierynomus:smbj` | Apache-2.0 |
| ASN.1 (transitive via SMBJ) | `com.hierynomus:asn-one` | Apache-2.0 |
| MBassador event bus (transitive via SMBJ) | `net.engio:mbassador` | MIT — Copyright (c) 2012 Benjamin Diedrichsen |
| smbj-rpc (DCE/RPC) | `com.rapid7.client:dcerpc` | BSD-3-Clause — Copyright (c) Rapid7, Inc. |
| Bouncy Castle provider | `org.bouncycastle:bcprov-jdk18on` | Bouncy Castle License (MIT) — Copyright (c) The Legion of the Bouncy Castle Inc. |
| SLF4J NOP binding | `org.slf4j:slf4j-nop` | MIT — Copyright (c) QOS.ch |
| OkHttp / Okio | `com.squareup.okhttp3:okhttp`, `com.squareup.okio:okio` | Apache-2.0 |
| Apache Commons Net | `commons-net:commons-net` | Apache-2.0 — includes software developed at The Apache Software Foundation |
| Apache Commons Compress | `org.apache.commons:commons-compress` | Apache-2.0 — includes software developed at The Apache Software Foundation |
| Apache Commons IO & Commons Codec (transitive via Commons Compress) | `commons-io:commons-io`, `commons-codec:commons-codec` | Apache-2.0 — includes software developed at The Apache Software Foundation |
| Guava & annotations (FailureAccess, J2ObjC, Error Prone, JSpecify) | `com.google.guava:guava`, `com.google.guava:failureaccess`, `com.google.j2objc:j2objc-annotations`, `com.google.errorprone:error_prone_annotations`, `org.jspecify:jspecify` | Apache-2.0 |

To regenerate the full dependency list:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## BSD-3-Clause (smbj-rpc)

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## MIT (Bouncy Castle, SLF4J, MBassador)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Apache-2.0

See [LICENSE](LICENSE).
