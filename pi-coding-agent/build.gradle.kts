plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

application {
    applicationName = "pi"
    mainClass = "works.earendil.pi.codingagent.MainKt"
}

dependencies {
    api(project(":pi-agent-core"))
    api(project(":pi-ai"))
    api(project(":pi-tui"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jline:jline:3.26.3")
    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
}

tasks.test {
    systemProperty("pi.project.root", rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("sessionJsonlOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.codingagent.session.SessionJsonlOracleKt"
    args(rootProject.file("migration/oracle/session-fixtures").absolutePath)
}

tasks.register<JavaExec>("codingMessageProjectionOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.codingagent.CodingMessageProjectionOracleKt"
    args(rootProject.file("migration/oracle/coding-messages.json").absolutePath)
}

tasks.register<JavaExec>("resourceLoadingOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.codingagent.ResourceLoadingOracleKt"
}

tasks.register<JavaExec>("packageResourcesOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.codingagent.PackageResourcesOracleKt"
}

tasks.register<JavaExec>("extensionRuntimeOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.codingagent.ExtensionRuntimeOracleKt"
    args(rootProject.file("migration/fixtures/extension-runtime/basic.ts").absolutePath)
}

tasks.register<JavaExec>("extensionJitiCompatibilityOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.codingagent.ExtensionJitiCompatibilityOracleKt"
    args(rootProject.file("migration/fixtures/extension-jiti-compat").absolutePath)
}

tasks.register<JavaExec>("extensionShortcutsOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.codingagent.ExtensionShortcutsOracleKt"
    args(rootProject.file("migration/fixtures/extension-shortcuts").absolutePath)
}
