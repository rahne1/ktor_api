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
    implementation("io.ktor:ktor-server-core:2.3.0")
    implementation("io.ktor:ktor-server-netty:2.3.0")
    implementation("io.ktor:ktor-server-host-common:2.3.0")
    implementation("io.ktor:ktor-server-status-pages:2.3.0")
    implementation("io.ktor:ktor-serialization-jackson:2.3.0")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.0")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    testImplementation("io.ktor:ktor-server-tests:2.3.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.10")
}

tasks.withType<Test> {
    useJUnitPlatform()
}