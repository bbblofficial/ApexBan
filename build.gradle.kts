plugins {
    id("com.gradleup.shadow") version "8.3.5" apply false
}

allprojects {
    group = "dev.apexban"
    version = providers.gradleProperty("pluginVersion").get()

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
