import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9.x: built-in Kotlin support is enabled by default, so no
// "kotlin-android" plugin is applied here, and the android {} extension is
// configured through the new typed DSL (extensions.configure<...>) instead
// of the old deprecated `android { }` block function.
plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

extensions.configure<ApplicationExtension> {
    namespace = "com.example.soundtovibro"
    compileSdk = 35
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Change this to your own applicationId before publishing.
        applicationId = "com.example.soundtovibro"
        minSdk = flutter.minSdkVersion
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // Replace with your own signing config before shipping a
            // release build — this keeps `flutter run --release` working
            // out of the box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// Replaces the old `android { kotlinOptions { jvmTarget = ... } }` block,
// which AGP 9 deprecated in favor of the Kotlin Gradle plugin's own
// compilerOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

flutter {
    source = "../.."
}
