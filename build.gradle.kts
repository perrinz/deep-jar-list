plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.perrinz"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.2.2")
    implementation("com.github.ajalt.mordant:mordant:2.2.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.perrinz.deepjarlist.DeepJarListKt")
}

tasks.jar {
    archiveBaseName.set("deepjarlist")
    archiveVersion.set("")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory()) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "com.perrinz.deepjarlist.DeepJarListKt"
        )
    }
}

val createDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Creates distribution zip with jar and shell script"
    
    archiveFileName.set("deepjarlist.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    
    from(tasks.jar)
    from("bin") {
        include("djl.sh")
        filePermissions {
            unix("755")
        }
    }
}

tasks.build {
    dependsOn(createDistribution)
}
