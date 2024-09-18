plugins {
    kotlin("jvm") version "2.0.10"
    id("io.ktor.plugin") version "2.3.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.ApplicationKt")
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.10")
    implementation("io.ktor:ktor-server-netty:2.3.10")
    implementation("ch.qos.logback:logback-classic:1.4.12")
}