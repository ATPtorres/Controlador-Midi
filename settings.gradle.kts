pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com[.]android([.].*)?")
                includeGroupByRegex("com[.]google[.]testing[.]platform([.].*)?")
                includeGroupByRegex("androidx([.].*)?")
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
                includeGroupByRegex("com[.]android([.].*)?")
                includeGroupByRegex("androidx([.].*)?")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "MIDI Performance Controller Pro"
include(":app", ":midi")
