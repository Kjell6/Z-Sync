import java.util.Properties
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

// See keystore.properties.example. Absent on a fresh clone and in CI, which is
// fine: only release builds need it, and they refuse to run without it below.
val hasReleaseSigning = keystoreProps["storeFile"] != null

android {
    namespace = "de.kjell.zencompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.kjell.zencompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.5"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // AGP 9.3+: R8 shrinking, optimization, and obfuscation, plus
            // resource shrinking. Default Android keep rules are included.
            // Play requires each DEX score at or above 25% once DEX exceeds 10 MB.
            optimization {
                enable = true
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("test") {
            // Leaf dirs so JSON lands at the classpath root (loaders use basename).
            resources.srcDir(rootProject.file("../shared/contract/fixtures/crypto"))
            resources.srcDir(rootProject.file("../shared/contract/fixtures/wire"))
            resources.srcDir(rootProject.file("../shared/contract/fixtures/auth"))
            resources.srcDir(rootProject.file("../shared/contract/http"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Without a signing config, assembleRelease and bundleRelease still "succeed"
// and quietly write an unsigned APK/AAB that no device and no Play upload will
// accept. Fail before anything is compiled, so those artifacts can never be
// produced by accident. This also fails a plain "gradle build", which pulls in
// assembleRelease. Escape hatch: ZSYNC_ALLOW_UNSIGNED=1
val allowUnsignedRelease =
    providers.environmentVariable("ZSYNC_ALLOW_UNSIGNED").orNull == "1"

gradle.taskGraph.whenReady {
    val unsignedRelease = allTasks.filter {
        it.name == "assembleRelease" || it.name == "bundleRelease"
    }
    if (unsignedRelease.isNotEmpty() && !hasReleaseSigning && !allowUnsignedRelease) {
        throw GradleException(
            """
            Release signing is not configured, so ${unsignedRelease.joinToString { it.name }} would
            write an unsigned artifact that nothing will install.
            Copy android/keystore.properties.example to android/keystore.properties and point
            storeFile at the release keystore, or add ZSYNC_ALLOW_UNSIGNED=1 to build an
            unsigned artifact on purpose.
            """.trimIndent()
        )
    }
}

dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.9.1")
    }

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.android.play:review-ktx:2.0.2")
    implementation("com.caverock:androidsvg-aar:1.4")
    // Adds the cookie banner script before a page's own scripts run.
    implementation("androidx.webkit:webkit:1.18.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
