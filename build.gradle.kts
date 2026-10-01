plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = "xaeros-base-map"
    version = libs.versions.mod.version.get()
    group = "tools.argus.basemaps"
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)

    // Xaero's World Map: compile-only, against a copy of the jar pre-remapped to Yarn (libs/). Plain
    // compileOnly rather than modCompileOnly for the same reason as Basilisk: Loom refuses to process some
    // Xaero-family jars built with a newer Loom, and a pre-remapped copy sidesteps that entirely. Nothing
    // from it is bundled; the real mod is required at runtime (fabric.mod.json "depends").
    file("libs").listFiles { f -> f.name.endsWith(".jar") }?.forEach { compileOnly(files(it)) }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to libs.versions.minecraft.get()
        )
        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Standalone pack converter (no Minecraft needed), e.g.
//   ./gradlew pack --args="webtiles path/to/tiles path/to/my-pack --range -30000,30000"
//   ./gradlew pack --args="pack big-map.png out-pack --bpp 1 --center 0,0"
tasks.register<JavaExec>("pack") {
    group = "basemaps"
    description = "Builds a Basemaps tile pack (see tools.argus.basemaps.tools.PackTool)."
    mainClass.set("tools.argus.basemaps.tools.PackTool")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "3g"
    workingDir = project.projectDir
}
