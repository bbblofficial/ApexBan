plugins {
    id("com.gradleup.shadow")
}

repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    implementation(project(":core"))

    // Compiled against the 1.8.8 API so no newer-than-1.8 method can be used by accident.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("net.md-5:bungeecord-chat:1.16-R0.4")
    compileOnly("com.google.guava:guava:31.1-jre")
    compileOnly("com.google.code.gson:gson:2.8.9")
    compileOnly("commons-lang:commons-lang:2.6")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName.set("ApexBan-Bukkit")
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.zaxxer.hikari", "dev.apexban.libs.hikari")
    relocate("org.yaml.snakeyaml", "dev.apexban.libs.snakeyaml")
    relocate("org.slf4j", "dev.apexban.libs.slf4j")
}

tasks.build { dependsOn(tasks.shadowJar) }
