
plugins {
    java
}

group = "ru.doksi"
version = "3.5.5"

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    compileOnly("net.luckperms:api:5.5")
    // PacketEvents is already installed on the target server and is used only for per-player filtering.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveFileName.set("EventHeads.jar")
}
