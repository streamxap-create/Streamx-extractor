plugins {
    kotlin("jvm") version "2.0.0"
    `maven-publish`
}

group = "com.streamx"
version = "1.4.0"

repositories {
    mavenCentral()
}

dependencies {
    // okhttp is part of the public API (ExtractorConfig.httpClient), so expose it to consumers
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
    // Android apps in this project compile for Java 11 - the jar must not need a newer JVM,
    // otherwise Gradle refuses to use it as a dependency.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.release.set(11)
}

// Saari dependencies ke saath ek hi JAR (Gradle ke bina use karne ke liye)
tasks.register<Jar>("fatJar") {
    group = "build"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
