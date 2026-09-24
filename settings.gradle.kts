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
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    versionCatalogs {
        create("libs") {
            from(files("libs.versions.toml"))
        }
    }
}



rootProject.name = "DispenseSureRetail"
include(":app")
include(":hl7Core")
