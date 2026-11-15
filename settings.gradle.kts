pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.jetbrains.kotlin.jvm")
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
        }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "CarBridge"
include(":app")
include(":aare")
project(":aare").projectDir = file("third_party/aare")
include(":libadb")
project(":libadb").projectDir = file("third_party/libadb")






