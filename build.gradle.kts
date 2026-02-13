plugins {
	java
	id("org.springframework.boot") version "4.0.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.koreatech"
version = "0.0.1-SNAPSHOT"
description = "한기대 컴공 졸업작품"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Starters
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")

	// Database
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")

	// Spatial Data (PostGIS support)
	implementation("org.hibernate.orm:hibernate-spatial:7.0.3.Final")
	implementation("org.locationtech.jts:jts-core:1.20.0")
	implementation("org.geolatte:geolatte-geom:1.9.1")

	// JSON Processing
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

	// File Upload
	implementation("commons-io:commons-io:2.15.1")

	// SQLite (RTAB-Map .db file reading)
	implementation("org.xerial:sqlite-jdbc:3.47.1.0")

	// HTTP Client for Python service communication
	implementation("org.springframework.boot:spring-boot-starter-webflux")

	// Swagger / OpenAPI
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")

	// Lombok
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Testcontainers
	testImplementation("org.testcontainers:testcontainers:1.20.4")
	testImplementation("org.testcontainers:junit-jupiter:1.20.4")
	testImplementation("org.testcontainers:postgresql:1.20.4")

	// Test Lombok
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
