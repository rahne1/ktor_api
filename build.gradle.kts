plugins {
    kotlin("jvm") version "2.0.10"
    id("io.ktor.plugin") version "2.3.0"
    kotlin("plugin.serialization") version "2.0.10"
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.ktor:ktor-server-core:3.0.0-rc-1")
    implementation("io.ktor:ktor-server-netty:3.0.0-rc-1")
    implementation("io.ktor:ktor-server-host-common:3.0.0-rc-1")
    implementation("io.ktor:ktor-server-status-pages:3.0.0-rc-1")
    implementation("io.ktor:ktor-serialization-jackson:3.0.0-rc-1")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.0-rc-1")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.chaquo.python:gradle:15.0.1")
    testImplementation("io.ktor:ktor-server-tests:2-0-0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.10")
}

tasks.withType<Test> {
    useJUnitPlatform()
}