pluginManagement {
    repositories {

// 【本地环境】以下仓库镜像仅用于加速本机构建，可随时删除。
// 官方源在国内下载 AGP/Compose 依赖经常卡住（实测卡在 766MB 不动），
// 阿里云镜像实测 20-40ms，官方源 1100-1700ms。
// 镜像不可用时 Gradle 会自动回落到后面的 mavenCentral/google()。

        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")

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
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")

        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "DingDingAuto"
include(":app")
