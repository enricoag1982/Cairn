// Shared configuration for every Cairn Java library (ADR-0015).

plugins {
    `java-library`
}

group = "io.github.enricoag1982.cairn"
version = "0.1.0-SNAPSHOT"

// Java 25 by default (ADR-0005). CI also tests the newest Java release with -Pcairn.javaVersion=<n>.
val javaVersion = providers.gradleProperty("cairn.javaVersion").map(String::toInt).orElse(25)
val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = javaVersion.map { JavaLanguageVersion.of(it) }
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}

// Gradle puts a dependency on the module path only if it has a module descriptor or an Automatic-Module-Name. RE2/J has
// neither (its module name, re2j, comes from its file name), so a module that requires it would not find it. Modules
// therefore get their whole compile classpath as module path, for compiling and for Javadoc.
if (file("src/main/java/module-info.java").exists()) {
    tasks.named<JavaCompile>("compileJava") {
        modularity.inferModulePath = false
        doFirst {
            options.compilerArgs.addAll(listOf("--module-path", classpath.asPath))
        }
    }
    tasks.named<Javadoc>("javadoc") {
        modularity.inferModulePath = false
        doFirst {
            (options as CoreJavadocOptions).modulePath = classpath.files.toList()
        }
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+UseCompactObjectHeaders")
}
