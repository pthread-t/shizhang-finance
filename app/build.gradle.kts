plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val stagingKeystorePath = providers.environmentVariable("ANDROID_STAGING_KEYSTORE_PATH").orNull
val stagingKeystorePassword = providers.environmentVariable("ANDROID_STAGING_KEYSTORE_PASSWORD").orNull
val stagingKeyAlias = providers.environmentVariable("ANDROID_STAGING_KEY_ALIAS").orNull
val stagingKeyPassword = providers.environmentVariable("ANDROID_STAGING_KEY_PASSWORD").orNull
val developmentAbi = providers.gradleProperty("billRecord.developmentAbi").orNull
val developmentApplicationIdSuffix = providers.gradleProperty("billRecord.developmentApplicationIdSuffix").orNull
    ?.also { require(it.matches(Regex("\\.[a-z][a-z0-9_]*"))) { "Development application ID suffix must look like .debug" } }
    ?: ".debug"
val stagingServerUrl = providers.gradleProperty("billRecord.stagingServerUrl").orNull
    ?.trimEnd('/')
    ?.also { require(Regex("^https://[^\\s\\\"]+$").matches(it)) { "Staging server URL must be a valid HTTPS URL" } }
    ?: "https://staging-ledger.example.com"

android {
    namespace = "com.billrecord.ledger"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.billrecord.ledger"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2-rc1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        developmentAbi?.let { abi ->
            ndk { abiFilters += abi }
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        if (listOf(stagingKeystorePath, stagingKeystorePassword, stagingKeyAlias, stagingKeyPassword).all { !it.isNullOrBlank() }) {
            create("staging") {
                storeFile = file(requireNotNull(stagingKeystorePath))
                storePassword = stagingKeystorePassword
                keyAlias = stagingKeyAlias
                keyPassword = stagingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = developmentApplicationIdSuffix
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://ledger.example.com\"")
            buildConfigField("boolean", "CLOUD_FIRST", "false")
            buildConfigField("boolean", "ALLOW_SERVER_URL_EDIT", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://ledger.example.com\"")
            buildConfigField("boolean", "CLOUD_FIRST", "false")
            buildConfigField("boolean", "ALLOW_SERVER_URL_EDIT", "true")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$stagingServerUrl\"")
            buildConfigField("boolean", "CLOUD_FIRST", "true")
            buildConfigField("boolean", "ALLOW_SERVER_URL_EDIT", "false")
            signingConfigs.findByName("staging")?.let { signingConfig = it }
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += setOf(
        "META-INF/AL2.0",
        "META-INF/LGPL2.1",
        "META-INF/INDEX.LIST",
        "META-INF/DEPENDENCIES",
    )
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":shared"))

    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.webkit:webkit:1.14.0")

    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.room:room-paging:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.paging:paging-compose:3.3.6")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 4.17.0 is the newest release compatible with this project's compileSdk 36;
    // 4.18.0 raises its AAR minimum compile SDK to 37.
    implementation("net.zetetic:sqlcipher-android:4.17.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("androidx.sqlite:sqlite-ktx:2.7.0")

    implementation("io.ktor:ktor-client-core:3.2.1")
    implementation("io.ktor:ktor-client-okhttp:3.2.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.1")
    implementation("io.ktor:ktor-client-auth:3.2.1")
    implementation("io.ktor:ktor-client-websockets:3.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
