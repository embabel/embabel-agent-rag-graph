// Gradle build for Drivine KSP code generation (invoked from Maven — see this module's pom.xml).
//
// Why: KSP is Gradle-first; Maven's third-party KSP plugin lags current Kotlin. This tiny Gradle
// build runs KSP over the @NodeFragment / @GraphView model classes and emits the type-safe query DSL;
// the Maven build then compiles build/generated/ksp/main/kotlin alongside src/main/kotlin.
//
// Keep the Kotlin version in sync with <kotlin.version> in the pom (both 2.3.21).
//
// Run manually: ./gradlew kspKotlin

plugins {
    kotlin("jvm") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.10"
}

group = "com.embabel.agent"
version = "0.3.0-SNAPSHOT"

// Versions come from the Maven build via -P properties (see the exec-maven-plugin in the pom), so the
// pom is the single source of truth and the KSP DSL is generated against the *same* versions it's later
// compiled against. The fallbacks keep a standalone `./gradlew kspKotlin` working.
val drivineVersion = (findProperty("drivineVersion") as String?) ?: "0.0.73"
val embabelAgentVersion = (findProperty("embabelAgentVersion") as String?) ?: "1.5.0-SNAPSHOT"

repositories {
    // mavenLocal first is deliberate — it mirrors Maven's local-m2-first resolution, so this codegen and
    // the main Maven compile resolve the *same* drivine / embabel artifacts. Preferring the remote here
    // (as is usually reproducible-by-default) would risk generating the DSL against a different model
    // snapshot than the one it's compiled against.
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://repo.embabel.com/artifactory/libs-snapshot") }
    maven { url = uri("https://repo.embabel.com/artifactory/libs-release") }
}

dependencies {
    implementation("org.drivine:drivine4j:$drivineVersion")
    ksp("org.drivine:drivine4j-codegen:$drivineVersion")

    // Enough for the model classes to type-resolve: rag.model / rag.store live in rag-core.
    implementation("com.embabel.agent:embabel-agent-rag-core:$embabelAgentVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
}

kotlin {
    compilerOptions {
        // -Xcontext-parameters: the generated DSL uses context parameters (Kotlin 2.2+).
        // -Xskip-metadata-version-check: consume embabel libs built with an older Kotlin metadata.
        freeCompilerArgs.addAll("-Xcontext-parameters", "-Xskip-metadata-version-check")
    }

    sourceSets {
        main {
            // Only the model package carries @NodeFragment / @GraphView.
            kotlin.srcDirs(
                "../src/main/kotlin/com/embabel/agent/rag/graph/model",
                "build/generated/ksp/main/kotlin",
            )
        }
    }
}
