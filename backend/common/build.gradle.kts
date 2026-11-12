plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

val grpcVersion: String by project
val damlBindingsVersion: String by project
val jjwtVersion: String by project
val kotlinxSerializationVersion: String by project
val kotlinLoggingVersion: String by project
val logbackVersion: String by project
val exposedVersion: String by project
val postgresqlVersion: String by project
val hikariVersion: String by project
val ktorVersion: String by project

val damlProjectDir = rootProject.projectDir.parentFile.resolve("daml")
val darFile = damlProjectDir.resolve(".daml/dist/data-sharing-reward-system-1.0.0.dar")
val codegenOutputDir = layout.buildDirectory.dir("generated/daml")

// Java bindings for the Daml templates, generated from the DAR
val damlCodegen by tasks.registering(Exec::class) {
    group = "daml"
    description = "Generate Java bindings from DAML DAR"

    workingDir = damlProjectDir

    doFirst {
        if (!darFile.exists()) {
            throw GradleException("DAR file not found at $darFile. Run 'daml build' in the daml directory first.")
        }
    }

    commandLine(
        "daml", "codegen", "java",
        "-o", codegenOutputDir.get().asFile.absolutePath,
        darFile.absolutePath + "=com.dsrs.daml"
    )

    inputs.file(darFile)
    outputs.dir(codegenOutputDir)
}

sourceSets {
    main {
        java {
            srcDir(codegenOutputDir)
        }
    }
}

tasks.named("compileJava") {
    dependsOn(damlCodegen)
}

tasks.named("compileKotlin") {
    dependsOn(damlCodegen)
}

dependencies {
    implementation(kotlin("stdlib"))

    api("com.daml:bindings-java:$damlBindingsVersion")
    api("io.grpc:grpc-netty:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")

    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-server-netty:$ktorVersion")
    api("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    api("io.ktor:ktor-server-cors:$ktorVersion")
    api("io.ktor:ktor-server-call-logging:$ktorVersion")
    api("io.ktor:ktor-server-status-pages:$ktorVersion")
    api("io.ktor:ktor-server-auth:$ktorVersion")
    api("io.ktor:ktor-server-auth-jwt:$ktorVersion")

    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.postgresql:postgresql:$postgresqlVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")

    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}
