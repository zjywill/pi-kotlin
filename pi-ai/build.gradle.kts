plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
}

tasks.register<JavaExec>("providerPayloadOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.ai.providers.ProviderPayloadOracleKt"
}

tasks.register<JavaExec>("providerStreamEventOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.ai.providers.ProviderStreamEventOracleKt"
    args(rootProject.file("migration/oracle/provider-stream-fixtures").absolutePath)
}
