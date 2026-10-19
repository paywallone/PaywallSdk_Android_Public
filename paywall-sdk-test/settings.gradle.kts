pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Paywall Android SDK from the public-sdk folder of this repository.
        maven {
            name = "PaywallSdk"
            url = uri("../public-sdk")
            content { includeGroup("paywall") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "paywall-sdk-test-tool"
include(":app")
