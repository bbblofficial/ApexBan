plugins {
    id("com.gradleup.shadow")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") { expand("version" to project.version) }
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName.set("MineStormBan-Velocity")
    archiveClassifier.set("")
    mergeServiceFiles()
    // Velocity ships its own slf4j; the injected Logger type must stay un-relocated.
    exclude("org/slf4j/**")
    relocate("com.zaxxer.hikari", "dev.minestormban.libs.hikari")
    relocate("org.yaml.snakeyaml", "dev.minestormban.libs.snakeyaml")
}

tasks.build { dependsOn(tasks.shadowJar) }
