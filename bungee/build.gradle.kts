plugins {
    id("com.gradleup.shadow")
}

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    compileOnly("net.md-5:bungeecord-api:1.20-R0.2")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("bungee.yml") { expand("version" to project.version) }
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName.set("MineStormBan-Bungee")
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.zaxxer.hikari", "dev.minestormban.libs.hikari")
    relocate("org.yaml.snakeyaml", "dev.minestormban.libs.snakeyaml")
    relocate("org.slf4j", "dev.minestormban.libs.slf4j")
}

tasks.build { dependsOn(tasks.shadowJar) }
