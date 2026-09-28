pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven { url = uri("https://jitpack.io"); content { includeGroup("com.github.teamnewpipe"); includeGroup("com.github.TeamNewPipe") } }; maven { url = uri("https://repo1.maven.org/maven2/") }; mavenCentral() }
}
rootProject.name = "DBDown"
include(":app")