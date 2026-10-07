dependencies {
    api("com.zaxxer:HikariCP:5.1.0")
    api("org.yaml:snakeyaml:2.2")
    api("org.slf4j:slf4j-api:1.7.36")

    runtimeOnly("org.xerial:sqlite-jdbc:3.46.1.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}
