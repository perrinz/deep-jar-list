plugins {
    java
    application
}

group = "com.perrinz"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

application {
    mainClass.set("com.perrinz.deepjarlist.DeepJarList")
}

tasks.jar {
    archiveBaseName.set("deepjarlist")
    archiveVersion.set("")
    manifest {
        attributes(
            "Main-Class" to "com.perrinz.deepjarlist.DeepJarList"
        )
    }
}

val createDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Creates distribution zip with jar and shell script"
    
    archiveFileName.set("deepjarlist.zip")
    destinationDirectory.set(file("$buildDir/distributions"))
    
    from(tasks.jar)
    from("bin") {
        include("djl.sh")
        fileMode = 0b111101101 // 755 in octal
    }
}

tasks.build {
    dependsOn(createDistribution)
}
