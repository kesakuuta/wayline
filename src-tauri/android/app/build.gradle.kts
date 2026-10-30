// Wayline Android app-module overlay. scripts/prepare-android.sh merges these dependencies
// into the Tauri-generated app/build.gradle.kts without replacing Tauri's Rust build plugin.
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.15.0")
    // sing-box libbox Android binding; API adapter is isolated in LibboxBridge.kt.
    implementation("com.github.singbox-android:libbox:1.14.0")
}
