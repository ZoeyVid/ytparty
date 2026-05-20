plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"

}

group = "com.mcyt"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.1.2-R0.1-SNAPSHOT") // Assuming placeholder for future version
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
