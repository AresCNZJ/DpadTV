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
    // 仓库声明在 settings 层，好让机器上的 ~/.gradle/init.d/aliyun-mirrors.gradle
    // 把阿里云镜像插到前面（否则 Maven Central 在这里很慢）。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dpadtv-native"
include(":app")
