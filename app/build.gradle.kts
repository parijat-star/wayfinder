import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

base.archivesName.set("Wayfinder")

android {
  namespace = "com.droid.wayfinder"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.droid.wayfinder"
    minSdk = 30
    targetSdk = 36
    versionCode = 11
    versionName = "2.0"
    // Pure Kotlin/JVM app: no native libs, so the single APK runs on x86_64 and arm64-v8a.
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }
  testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,INDEX.LIST,LICENSE*,NOTICE*}"
      excludes += "META-INF/versions/**"
      excludes += "META-INF/*.kotlin_module"
    }
  }
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation("io.coil-kt.coil3:coil-compose:3.3.0")
  implementation("io.coil-kt.coil3:coil-video:3.3.0")

  implementation("com.hierynomus:smbj:0.14.0")
  implementation("com.rapid7.client:dcerpc:0.12.0")
  // dcerpc drags in guava 18 (CVE-2018-10237, CVE-2020-8908, CVE-2023-2976); it only uses stable io/base APIs.
  implementation("com.google.guava:guava:33.7.2-android")
  // 1.80 is affected by CVE-2025-14813 (fixed in 1.80.2 / 1.81.1+).
  implementation("org.bouncycastle:bcprov-jdk18on:1.86")
  implementation("org.slf4j:slf4j-nop:1.7.36")
  // WebDAV (PROPFIND/GET/PUT/MKCOL/MOVE/DELETE) and FTP/FTPS network locations.
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("commons-net:commons-net:3.13.0")
  implementation("org.apache.commons:commons-compress:1.27.1")

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // Real XmlPullParser implementation for the WebDAV multistatus parser tests (android.jar only has stubs).
  testImplementation("net.sf.kxml:kxml2:2.3.0")
  // QA: headless Robolectric + Compose UI tests, WebDAV (MockWebServer) and FTP (embedded Apache FtpServer) integration tests.
  testImplementation(composeBom)
  testImplementation("org.robolectric:robolectric:4.16")
  testImplementation("androidx.test:core-ktx:1.7.0")
  testImplementation("androidx.test.ext:junit:1.3.0")
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation("org.apache.ftpserver:ftpserver-core:1.2.0")
}

configurations.all {
  // dcerpc drags in an old BouncyCastle that clashes with bcprov-jdk18on.
  exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
  // guava 18 (via dcerpc) already contains ListenableFuture.
  exclude(group = "com.google.guava", module = "listenablefuture")
}
