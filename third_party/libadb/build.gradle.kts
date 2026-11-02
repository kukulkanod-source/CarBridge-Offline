plugins { id("com.android.library") }

android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 34
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1")
    testImplementation("junit:junit:4.13.2")
}
