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
    implementation("com.google.auth:google-auth-library-oauth2-http:1.30.1")
    implementation("com.github.luben:zstd-jni:1.5.7-7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(platform("software.amazon.awssdk:bom:2.49.1"))
    implementation("software.amazon.awssdk:bedrockruntime")
    implementation("software.amazon.awssdk:netty-nio-client")
    implementation("software.amazon.awssdk:sso")
    implementation("software.amazon.awssdk:ssooidc")
    implementation("software.amazon.awssdk:sts")

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

tasks.register<JavaExec>("modelCatalogRuntimeOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.ai.providers.ModelCatalogRuntimeOracleKt"
}

tasks.register<JavaExec>("openAICodexOAuthOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.ai.providers.OpenAICodexOAuthOracleKt"
}

tasks.register<JavaExec>("githubCopilotOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "works.earendil.pi.ai.providers.GitHubCopilotOracleKt"
}
