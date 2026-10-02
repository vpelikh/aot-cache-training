pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
	}
}

rootProject.name = "aot-cache-training"

include("aot-cache-training")
include("aot-cache-training-trainer")
include("aot-cache-training-maven-plugin")
include("aot-cache-training-gradle-plugin")