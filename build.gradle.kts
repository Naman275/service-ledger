plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.2.21" apply false
}

allprojects {
    providers.gradleProperty("ledgerBuildRoot").orNull?.let { outputRoot ->
        layout.buildDirectory.set(File(outputRoot, if (this == rootProject) "root" else name))
    }
}
