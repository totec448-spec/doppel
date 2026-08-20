plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePath = providers.environmentVariable("DOPPEL_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("DOPPEL_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("DOPPEL_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("DOPPEL_RELEASE_KEY_PASSWORD").orNull
val distributionSigningConfigured = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val generatedThirdPartyNoticesDir = layout.buildDirectory.dir("generated/third-party-notices")
val generateThirdPartyNotices = tasks.register<Copy>("generateThirdPartyNotices") {
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("LICENSES")) {
        into("licenses")
    }
    into(generatedThirdPartyNoticesDir)
}

android {
    namespace = "de.totec.doppel"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.totec.doppel"
        minSdk = 28
        targetSdk = 36
        // Doppel starts its own version line. The rename changed the application ID, so no
        // installed copy of the old build can upgrade into this one and no version code has to
        // stay monotonic across the two.
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (distributionSigningConfigured) {
            create("distribution") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Local direct installs keep the existing debug signature so adb can upgrade
            // in place. Public distribution must use assembleDistributionRelease, which
            // fails closed unless all protected signing variables are supplied.
            signingConfig = if (distributionSigningConfigured) {
                signingConfigs.getByName("distribution")
            } else {
                signingConfigs.getByName("debug")
            }
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("diagnostic") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            // Android deliberately disables optimization for debuggable variants.
            isMinifyEnabled = false
            isShrinkResources = false
            versionNameSuffix = "-diag"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    // The database migration test is instrumented — it needs a real SQLite and a real
    // Context — so without a device it is a test that exists and never runs. A managed
    // device lets CI create one on demand. The ATD image is deliberate: it ships without
    // the Play services and UI layers this test has no use for, which is most of what an
    // emulator otherwise spends its boot on.
    testOptions {
        managedDevices {
            localDevices {
                create("pixel2api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                    // Stated rather than defaulted: AGP currently assumes x86 and warns that
                    // it will assume arm64-v8a in 10.0, which would silently change which
                    // image this needs. x86 is the right answer here even though the app's
                    // only native library is arm64-v8a (gomobile binds a single target), on
                    // the narrow grounds that the instrumented suite is the database
                    // migration and touches no gomobile-generated class — nothing calls
                    // System.loadLibrary, so there is no arm64 code to fail to load. An
                    // instrumented test that does reach the bridge cannot run on this device
                    // and would need an arm64 image, which on an x86 runner means full
                    // emulation rather than virtualisation.
                    testedAbi = "x86"
                }
            }
        }
    }
    sourceSets.getByName("main").assets.directories.add(
        generatedThirdPartyNoticesDir.get().asFile.absolutePath,
    )
}

val verifyDistributionSigning = tasks.register("verifyDistributionSigning") {
    group = "verification"
    description = "Fails unless protected distribution signing credentials are configured."
    doLast {
        // Read at execution time without capturing the build-script Provider values. Apart from
        // keeping the task configuration-cache safe, this prevents secret values from becoming
        // Gradle task inputs or cache metadata.
        val configured =
            listOf(
                System.getenv("DOPPEL_RELEASE_KEYSTORE"),
                System.getenv("DOPPEL_RELEASE_STORE_PASSWORD"),
                System.getenv("DOPPEL_RELEASE_KEY_ALIAS"),
                System.getenv("DOPPEL_RELEASE_KEY_PASSWORD"),
            ).all { !it.isNullOrBlank() }
        check(configured) {
            "Distribution signing is not configured. Set DOPPEL_RELEASE_KEYSTORE, " +
                "DOPPEL_RELEASE_STORE_PASSWORD, DOPPEL_RELEASE_KEY_ALIAS, and " +
                "DOPPEL_RELEASE_KEY_PASSWORD."
        }
    }
}

tasks.register("assembleDistributionRelease") {
    group = "build"
    description = "Builds a non-debuggable release and requires protected distribution signing."
    dependsOn(verifyDistributionSigning, "assembleRelease")
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    mustRunAfter(verifyDistributionSigning)
}

tasks.matching {
    it.name.contains("lint", ignoreCase = true) ||
        (it.name.startsWith("merge") && it.name.endsWith("Assets"))
}.configureEach {
    dependsOn(generateThirdPartyNotices)
}

dependencies {
    implementation(files("libs/nativewa.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
