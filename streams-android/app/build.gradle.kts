import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Your Supabase keys live in local.properties (never committed to git).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String) = (localProps.getProperty(name) ?: "").trim()

// The Streams Supabase project. The anon key is public by design (every APK contains it);
// all protection comes from the row-level security rules in supabase/schema.sql.
// local.properties values, when set, override these.
val supabaseUrl = prop("SUPABASE_URL").ifEmpty { "https://kpnfncydvpzlrcjplazh.supabase.co" }
val supabaseAnonKey = prop("SUPABASE_ANON_KEY").ifEmpty {
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImtwbmZuY3lkdnB6bHJjanBsYXpoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA1NzMzODYsImV4cCI6MjEwNjE0OTM4Nn0.PUY0H_NywecExxdKCmUzwz_lhJKnI0jgjvRB546bQbI"
}

android {
    namespace = "com.streams.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.streams.app"
        minSdk = 26
        targetSdk = 36
        // Every GitHub build gets its own number, so a newer APK always installs as an update
        // and Profile shows exactly which build is on the phone.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + buildNumber
        versionName = "1.1.$buildNumber"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    // Fixed test-signing key (app/debug.keystore, standard Android debug passwords) so every
    // GitHub-built APK installs over the previous one instead of failing with "App not installed".
    // Not for Play Store releases — those need your own private upload key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Two apps from one codebase:
    //  • customer — "Streams", what everyone installs. No SMS / notification access, so Google
    //    Play Protect lets it install from WhatsApp or a browser.
    //  • admin    — "Streams Admin", only for the owner's phone. Same app plus Auto-verify
    //    payments (reads the bank's "credited" SMS / UPI notifications). See src/admin/.
    flavorDimensions += "role"
    productFlavors {
        create("customer") {
            dimension = "role"
            buildConfigField("boolean", "AUTO_VERIFY", "false")
        }
        create("admin") {
            dimension = "role"
            applicationIdSuffix = ".admin"
            buildConfigField("boolean", "AUTO_VERIFY", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Android + Compose
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1") // uploads detected payments reliably

    // Supabase (auth, database, file storage)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.6"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Video player + images
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
}
