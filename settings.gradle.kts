pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack builds any GitHub repo on request: only the author's own libraries come from it.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.HereLiesAz.*") }
        }
    }
}

rootProject.name = "CleanUnderwear"
include(":app")
