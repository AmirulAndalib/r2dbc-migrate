# R2DBC migration tool
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/name.nkonev.r2dbc-migrate/r2dbc-migrate-spring-boot-starter/badge.svg)](https://central.sonatype.com/namespace/name.nkonev.r2dbc-migrate)
[![Docker Image Version (latest semver)](https://img.shields.io/docker/v/nkonev/r2dbc-migrate)](https://hub.docker.com/r/nkonev/r2dbc-migrate/tags)
[![Build Status](https://github.com/nkonev/r2dbc-migrate/workflows/Java%20CI%20with%20Maven/badge.svg)](https://github.com/nkonev/r2dbc-migrate/actions)

Inspired by [this](https://spring.io/blog/2020/03/12/spring-boot-2-3-0-m3-available-now) announcement. R2DBC [page](https://r2dbc.io/).

## Supported databases
* PostgreSQL
* Microsoft SQL Server
* * H2
* MariaDB
* ~~MySQL~~ 
In the moment of writing MySQL driver still hasn't migrated to R2DBC 1.0. Tracking [issue](https://github.com/mirromutth/r2dbc-mysql/pull/249).
You can try MariaDB driver with explicit dialect
```yaml
r2dbc:
  migrate:
    dialect: mariadb 
```

It also supports user-provided dialect. You can pass implementation of `SqlQueries` interface to the `migrate()` method. If you use Spring Boot, just define a bean of type `SqlQueries`. Example [SimplePostgresqlDialect](https://github.com/nkonev/r2dbc-migrate/commit/86296acf0bbc6a7f4cbffe493cd2c3060d7885e2#diff-ed1c7d95fcf0c0921c5b87c0d91c3f01a7c686f5e69059e8621f55de9e95a334R369).

## Features
* Convention-based file names, for example `V3__insert_to_customers__split,nontransactional.sql`
* Pre-migration scripts, for example `V0__create_schemas__premigration.sql`. Those scripts are invoked every time before entire migration process(e. g. before migration tables created), so you need to make them idempotent. You can use zero or negative version number(s): `V-1__create_schemas__nontransactional,premigration.sql`. See [example](https://github.com/nkonev/r2dbc-migrate/tree/master/r2dbc-migrate-core/src/test/resources/migrations/postgresql_premigration).
* It waits until database has been started, then performs test query, and validates its result. This can be useful for the initial data loading into database with docker-compose
* Supports migrations files larger than `-Xmx`: file will be split line-by-line (`split` modifier), then it will be loaded by chunks into the database
* Supports lock, that make you able to start number of replicas your microservice, without care of migrations collide each other. Database-specific lock tracking [issue](https://github.com/nkonev/r2dbc-migrate/issues/28).
* Each migration runs in the separated transaction by default
* It also supports `nontransactional` migrations, due to SQL Server 2017 prohibits `CREATE DATABASE` in the transaction
* Docker image
* First-class Spring Boot integration, see example below
* Also you can use this library without Spring (Boot), see library example below
* This library tends to be non-invasive, consequently it intentionally doesn't try to parse SQL and make some decisions relying on. So (in theory) you can freely update database and driver's version

All available configuration options are in [R2dbcMigrateProperties](https://github.com/nkonev/r2dbc-migrate/blob/master/r2dbc-migrate-core/src/main/java/name/nkonev/r2dbc/migrate/core/R2dbcMigrateProperties.java) class.
Their descriptions are available in your IDE Ctrl+Space help or in [spring-configuration-metadata.json](https://github.com/nkonev/r2dbc-migrate/blob/master/r2dbc-migrate-spring-boot-starter/src/main/resources/META-INF/spring-configuration-metadata.json) file.

## Limitations
* Currently, this library heavy relies on upsert-like syntax like `CREATE TABLE ... ON CONFLICT DO NOTHING`.
Because this syntax isn't supported in H2 in PostresSQL compatibility mode, as a result, this library [can't be](https://github.com/nkonev/r2dbc-migrate/issues/21) run against H2 with `MODE=PostgreSQL`. Use [testcontainers](https://github.com/nkonev/r2dbc-migrate-example) with real PostgreSQL.
* Only forward migrations are supported. No `back migrations`.
* No [checksum](https://github.com/nkonev/r2dbc-migrate/issues/5) validation. As a result [repeatable](https://github.com/nkonev/r2dbc-migrate/issues/9) migrations aren't supported.

## Compatilility (r2dbc-migrate, R2DBC Spec, Java, Spring Boot ...)
See [here](https://github.com/nkonev/r2dbc-migrate/issues/27#issuecomment-1404878933)

## Download

### Docker
```
docker pull nkonev/r2dbc-migrate:latest
```

### Spring Boot Starter
```xml
<dependency>
  <groupId>name.nkonev.r2dbc-migrate</groupId>
  <artifactId>r2dbc-migrate-spring-boot-starter</artifactId>
  <version>VERSION</version>
</dependency>
```

### Only library
```xml
<dependency>
    <groupId>name.nkonev.r2dbc-migrate</groupId>
    <artifactId>r2dbc-migrate-core</artifactId>
    <version>VERSION</version>
</dependency>
```

If you use library, you need also use some implementation of `r2dbc-migrate-resource-reader-api`, for example:
```xml
<dependency>
    <groupId>name.nkonev.r2dbc-migrate</groupId>
    <artifactId>r2dbc-migrate-resource-reader-reflections</artifactId>
    <version>VERSION</version>
</dependency>
```
See `Library example` below.

### Standalone application

If you want to build your own docker image you will be able to do this
```bash
curl -Ss https://repo.maven.apache.org/maven2/name/nkonev/r2dbc-migrate/r2dbc-migrate-standalone/VERSION/r2dbc-migrate-standalone-VERSION.jar > /tmp/migrate.jar
```

## Spring Boot Example
https://github.com/nkonev/r2dbc-migrate-example

## Library example
https://github.com/nkonev/r2dbc-migrate-example/tree/library

## docker-compose v3 example
```yml
version: '3.7'
services:
  migrate:
    image: nkonev/r2dbc-migrate:VERSION
    environment:
      _JAVA_OPTIONS: -Xmx128m
      spring.r2dbc.url: "r2dbc:pool:mssql://mssqlcontainer:1433"
      spring.r2dbc.username: sa
      spring.r2dbc.password: "yourSuperStrong(!)Password"
      r2dbc.migrate.resourcesPath: "file:/migrations/*.sql"
      r2dbc.migrate.validationQuery: "SELECT collation_name as result FROM sys.databases WHERE name = N'master'"
      r2dbc.migrate.validationQueryExpectedResultValue: "Cyrillic_General_CI_AS"
    volumes:
      - ./migrations:/migrations
```
