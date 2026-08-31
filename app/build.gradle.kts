import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore/keystore.properties")
    if (propsFile.exists()) load(propsFile.inputStream())
}

android {
    namespace = "com.mitas.ppnam.station1aa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mitas.ppnam.station1aa"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // keystore/keystore.properties is untracked; machines without it
            // (e.g. CI, which only builds debug) still need to configure.
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        // Settings' Diagnostics card shows the app version from BuildConfig, like Station 2.
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
    }
}

tasks.register("cleanDuplicateResources") {
    doLast {
        delete("src/main/res/drawable/writing.xml")
        println("Cleanup: Deleted duplicate writing.xml")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.netty.codec.http)

    // Chainway SDK
    implementation(fileTree("libs") { include("*.aar") })

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests — the mockable android.jar only has stubs.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
