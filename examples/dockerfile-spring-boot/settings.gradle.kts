pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // Make the plugin from this repository available to the plugins {} block.
    includeBuild("../..")
}

// Make the library modules available as dependencies.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.vpelikh:aot-cache-training")).using(project(":aot-cache-training"))
        substitute(module("io.github.vpelikh:aot-cache-training-trainer")).using(project(":aot-cache-training-trainer"))
    }
}

rootProject.name = "dockerfile-spring-boot-example"