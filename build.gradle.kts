plugins {
	java
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.devpath"
version = "0.0.1-SNAPSHOT"
description = "DevPath AI LCS service (learning context snapshots)"

val devpathSharedVersion = providers.gradleProperty("devpathSharedVersion").get()
val devpathSharedCoordinate = "ai.devpath:devpath-shared:$devpathSharedVersion"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	providers.gradleProperty("immutableSharedRepository").orNull?.let { repository ->
		maven { url = uri(repository) }
	}
	mavenCentral()
	maven {
		url = uri("https://maven.pkg.github.com/DevPathAi/devpath-shared")
		credentials {
			username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
			password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
		}
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation(devpathSharedCoordinate)
	runtimeOnly("org.postgresql:postgresql")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-flyway")
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.flywaydb:flyway-database-postgresql")
	testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
