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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // NewPipeExtractor (and its nanojson fork) are only published on JitPack.
        maven(url = "https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "AudioSaver"

include(":core")           // pure Kotlin/JVM: link parsing, download pipeline, MP4 tagging
include(":engine-newpipe") // the only module that knows about NewPipeExtractor
include(":app")            // Android UI, foreground service, MediaStore
