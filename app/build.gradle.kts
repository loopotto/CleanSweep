// build.gradle.kts (app)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.mikepenz.aboutlibraries.plugin")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.cleansweep"
    compileSdk = 36

    kotlin {
        jvmToolchain(17)
    }
    signingConfigs {
        create("release") {
            // Retrieve keystore path and alias from gradle.properties
            // Android Studio will prompt for passwords when generating signed builds.
            storeFile = file(project.properties["CLEANSWEEP_RELEASE_STORE_FILE"] as String)
            keyAlias = project.properties["CLEANSWEEP_RELEASE_KEY_ALIAS"] as String
        }
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/java")
    }

    defaultConfig {
        applicationId = "com.cleansweep"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
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
            resources.excludes.add("META-INF/AL2.0")
            resources.excludes.add("META-INF/LGPL2.1")
            resources.excludes.add("META-INF/DEPENDENCIES")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val appName = "cleansweep"
            val versionName = output.versionName.get()
            val buildType = variant.buildType

            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "$appName-v$versionName-$buildType.apk"
            )
        }
    }
}

// Configure Kotlin compiler to output more warnings
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // Enable reporting of unused code for optimization purposes
        freeCompilerArgs.add("-Xreport-unused-for-optimization")
        // Enable all Kotlin compiler lint warnings
        freeCompilerArgs.add("-Xlint:all")
    }
}



// Minimal configuration for the AboutLibraries plugin.
// This enables the plugin to scan dependencies and generate license data.
aboutLibraries {

}

dependencies {
    val composeBomVersion = "2026.01.01"
    implementation(platform("androidx.compose:compose-bom:$composeBomVersion"))
    androidTestImplementation(platform("androidx.compose:compose-bom:$composeBomVersion"))

    // Core Android dependencies
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.core:core-splashscreen:1.2.0")

    // WorkManager
    val work_version = "2.11.1"
    implementation("androidx.work:work-runtime-ktx:$work_version")

    // Hilt integration for WorkManager
    implementation("androidx.hilt:hilt-work:1.3.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.compose.material3:material3-window-size-class:1.4.0")

    implementation("com.google.dagger:hilt-android:2.59")
    ksp("com.google.dagger:hilt-compiler:2.59")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")

    // Coil
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    // ExoPlayer (Media3)
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("androidx.media3:media3-common:1.9.2")

    // Permissions handling
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // DocumentFile for folder/file operations
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Licenses
    implementation("com.mikepenz:aboutlibraries-compose:12.2.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.2")
}
