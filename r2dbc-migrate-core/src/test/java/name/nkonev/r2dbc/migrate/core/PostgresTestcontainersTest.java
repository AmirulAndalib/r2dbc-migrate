package name.nkonev.r2dbc.migrate.core;

import io.r2dbc.spi.*;
import name.nkonev.r2dbc.migrate.reader.ReflectionsClasspathResourceReader;
import name.nkonev.r2dbc.migrate.reader.SpringResourceReader;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static name.nkonev.r2dbc.migrate.core.R2dbcMigrate.getResultSafely;
import static name.nkonev.r2dbc.migrate.core.TestConstants.waitTestcontainersSeconds;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PostgresTestcontainersTest {
    final static int POSTGRESQL_PORT = 5432;
    private static final String POSTGRES_QUERY_LOGGER = "io.r2dbc.postgresql.QUERY";
    static GenericContainer container;

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(PostgresTestcontainersTest.class);

    @BeforeEach
    public void beforeEach() {
        container = new GenericContainer("postgres:14.1-alpine3.14")
            .withExposedPorts(POSTGRESQL_PORT)
            .withEnv("POSTGRES_PASSWORD", "postgresqlPassword")
            .withClasspathResourceMapping("/docker/postgresql/docker-entrypoint-initdb.d", "/docker-entrypoint-initdb.d", BindMode.READ_ONLY)
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*database system is ready to accept connections.*\\s")
                .withTimes(2).withStartupTimeout(Duration.ofSeconds(waitTestcontainersSeconds)));
        container.start();
    }

    @AfterEach
    public void afterEach() {
        container.stop();
    }

    private ConnectionFactory makeConnectionMono(int port) {
        ConnectionFactory connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql")
            .option(HOST, "127.0.0.1")
            .option(PORT, port)
            .option(USER, "r2dbc")
            .option(PASSWORD, "r2dbcPazZw0rd")
            .option(DATABASE, "r2dbc")
            .build());
        return connectionFactory;
    }

    static class Customer {
        String firstName, secondName;
        int id;

        public Customer(String firstName, String secondName, int id) {
            this.firstName = firstName;
            this.secondName = secondName;
            this.id = id;
        }
    }

    private static SpringResourceReader springResourceReader = new SpringResourceReader();

    @Test
    public void testThatTransactionsWrapsQueriesAndTransactionsAreNotNested() {
        // create and start a ListAppender
        try (LogCaptor logCaptor = LogCaptor.forName(POSTGRES_QUERY_LOGGER)) {
            logCaptor.setLogLevelToDebug();

            R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
            properties.setDialect(Dialect.POSTGRESQL);
            properties.setResourcesPath("classpath:/migrations/postgresql/*.sql");
            properties.setPreferDbSpecificLock(false);

            Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
            R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, null, null).block();

            // get log
            // make asserts
            assertTrue(
                hasSubList(logCaptor.getDebugLogs(), Arrays.asList(
                    "BEGIN",
                    "create table if not exists \"migrations\"(id bigint primary key, description text); create table if not exists \"migrations_lock\"(id int primary key, locked boolean not null); insert into \"migrations_lock\"(id, locked) values (1, false) on conflict (id) do nothing",
                    "COMMIT",
                    "BEGIN",
                    "update \"migrations_lock\" set locked = true where id = 1 and locked = false",
                    "COMMIT",
                    "select max(id) from \"migrations\"",
                    "BEGIN",
                    "CREATE TABLE customer (id SERIAL PRIMARY KEY, first_name VARCHAR(255), last_name VARCHAR(255))",
                    "COMMIT",
                    "BEGIN",
                    "insert into \"migrations\"(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "insert into customer(first_name, last_name) values ('Muhammad', 'Ali'), ('Name', 'Фамилия');",
                    "COMMIT",
                    "BEGIN",
                    "insert into \"migrations\"(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "insert into customer(first_name, last_name) values ('Customer', 'Surname 1');; insert into customer(first_name, last_name) values ('Customer', 'Surname 2');; insert into customer(first_name, last_name) values ('Customer', 'Surname 3');; insert into customer(first_name, last_name) values ('Customer', 'Surname 4');",
                    "COMMIT",
                    "BEGIN",
                    "insert into \"migrations\"(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "create table example(id serial primary key);\n",
                    "COMMIT",
                    "BEGIN",
                    "insert into \"migrations\"(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "update \"migrations_lock\" set locked = false where id = 1",
                    "COMMIT"
                )));
            logCaptor.setLogLevelToInfo();
        }
    }

    @Test
    public void testThatLockIsReleasedAfterError() {
        // create and start a ListAppender
        try (LogCaptor logCaptor = LogCaptor.forName(POSTGRES_QUERY_LOGGER)) {
            logCaptor.setLogLevelToDebug();

            R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
            properties.setDialect(Dialect.POSTGRESQL);
            properties.setResourcesPath("classpath:/migrations/postgresql_error/*.sql");
            properties.setPreferDbSpecificLock(false);

            Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);

            RuntimeException thrown = Assertions.assertThrows(
                RuntimeException.class,
                () -> {
                    R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, null, null).block();
                },
                "Expected exception to throw, but it didn't"
            );
            Assertions.assertTrue(thrown.getMessage().contains("syntax error at or near \"ololo\""));

            // make asserts
            assertTrue(
                hasSubList(logCaptor.getDebugLogs(), Arrays.asList(
                    "BEGIN",
                    "insert into customer(first_name, last_name) values\n"
                        + "ololo\n"
                        + "('Muhammad', 'Ali'), ('Name', 'Фамилия');"
                )));

            Mono<Boolean> r = Mono.usingWhen(
                makeConnectionMono(mappedPort).create(),
                connection -> Mono.from(connection.createStatement("select locked from \"migrations_lock\" where id = 1").execute())
                    .flatMap(o -> Mono.from(o.map(getResultSafely("locked", Boolean.class, null)))),
                Connection::close);
            Boolean block = r.block();
            Assertions.assertNotNull(block);
            Assertions.assertFalse(block);
            logCaptor.setLogLevelToInfo();
        }
    }

    @Test
    public void testValidationResultOk() {
        R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
        properties.setValidationQuery("select 'super value' as validation_result");
        properties.setValidationQueryExpectedResultValue("super value");
        properties.setConnectionMaxRetries(1);
        properties.setDialect(Dialect.POSTGRESQL);
        properties.setResourcesPath("classpath:/migrations/postgresql/*.sql");

        Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
        R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, null, null).block();
    }

    @Test
    public void testDefaults() {
        R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
        properties.setResourcesPath("classpath:/migrations/postgresql/*.sql");
        Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
        R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, null, null).block();
    }

    @Test
    public void testTwoModesWithSubstitution() {
        System.setProperty("REPLACEMENT", "D");

        R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
        var source1 = BunchOfResourcesEntry.ofConventionallyNamedFiles("classpath:/migrations/postgresql_substitute/*.sql");
        var source2 = BunchOfResourcesEntry.ofJustFile(11, "An additional one", "classpath:/migrations/postgresql_substitute_append/additional.sql", true);
        properties.setResources(List.of(source1, source2));
        Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
        R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, null, null).block();

        ConnectionFactory connectionFactory = makeConnectionMono(mappedPort);
        Flux<Customer> clientFlux = Flux.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(connection.createStatement("select * from customer order by id").execute())
                .flatMap(o -> o.map((row, rowMetadata) -> {
                    return new Customer(
                        row.get("first_name", String.class),
                        row.get("last_name", String.class),
                        row.get("id", Integer.class)
                    );
                })),
            Connection::close
        );
        List<Customer> clients = clientFlux.collectList().block();

        Assertions.assertTrue(clients.stream().anyMatch(client -> client.firstName.equals("Customer P") && client.secondName.equals("Johnny D")));
        Assertions.assertTrue(clients.stream().anyMatch(client -> {
            var sn = client.secondName.toLowerCase();
            return client.firstName.equals("Customer OS") && Set.of("linux", "mac", "windows").stream().anyMatch(sn::contains);
        }));
    }

    @Test
    public void testDatabaseValidationResultFail() {
        RuntimeException thrown = Assertions.assertThrows(
            RuntimeException.class,
            () -> {
                R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
                properties.setValidationQuery("select 'not super value' as validation_result");
                properties.setValidationQueryExpectedResultValue("super value");
                properties.setConnectionMaxRetries(1);
                properties.setDialect(Dialect.POSTGRESQL);
                properties.setResourcesPath("classpath:/migrations/postgresql/*.sql");

                Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
                R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, null, null).block();
            },
            "Expected exception to throw, but it didn't"
        );

        assertTrue(thrown.getMessage().contains("Retries exhausted"));
        assertTrue(thrown.getCause().getMessage().contains("Not matched result of test query"));
    }

    @EnabledIfSystemProperty(named = "enableOomTests", matches = "true")
    @Test
    public void testSplittedLargeMigrationsFitsInMemory() throws IOException {
        // _JAVA_OPTIONS: -Xmx128m
        var generatedMigrationToDir = new File("./target/test-classes/oom_migrations");
        generatedMigrationToDir.mkdirs();

        var fromDir = new File("./src/test/resources/migrations/postgresql");

        // copy to "to dir"
        for (String sourceFileName : fromDir.list()) {
            var sourceFile = fromDir.toPath().resolve(sourceFileName);
            var destFile = generatedMigrationToDir.toPath().resolve(sourceFileName);
            Files.copy(sourceFile, destFile);
        }

        File generatedMigration = new File(generatedMigrationToDir, "V20__generated__split.sql");
        if (!generatedMigration.exists()) {
            LOGGER.info("Generating large file");
            PrintWriter pw = new PrintWriter(new FileWriter(generatedMigration));
            for (int i = 0; i < 6_000_000; i++) {
                pw.println(String.format("insert into customer(first_name, last_name) values ('Generated Name %d', 'Generated Surname %d');", i, i));
            }
            pw.close();
            LOGGER.info("Generating large file completed");
        }

        R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
        properties.setDialect(Dialect.POSTGRESQL);
        properties.setResourcesPath("file:./target/test-classes/oom_migrations/*.sql");

        Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
        R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, null, null).block();
    }


    @Test
    public void testOtherMigrationSchema() {
        R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
        properties.setMigrationsSchema("my scheme");
        properties.setMigrationsTable("my migrations");
        properties.setMigrationsLockTable("my migrations lock");
        properties.setResourcesPath("classpath:/migrations/postgresql/*.sql");
        properties.setPreferDbSpecificLock(false);
        Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
        ConnectionFactory connectionFactory = makeConnectionMono(mappedPort);

        Mono<Long> integerMono = Mono.usingWhen(
            connectionFactory.create(),
            connection -> Mono
                .from(connection.createStatement("create schema \"my scheme\"").execute())
                .flatMap(o -> Mono.from(o.getRowsUpdated())),
            Connection::close
        );
        integerMono.block();

        R2dbcMigrate.migrate(connectionFactory, properties, springResourceReader, null, null).block();

        Flux<Customer> clientFlux = Flux.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(connection.createStatement("select * from customer order by id").execute())
                .flatMap(o -> o.map((row, rowMetadata) -> {
                    return new Customer(
                        row.get("first_name", String.class),
                        row.get("last_name", String.class),
                        row.get("id", Integer.class)
                    );
                })),
            Connection::close
        );
        Customer client = clientFlux.blockLast();

        Assertions.assertEquals("Customer", client.firstName);
        Assertions.assertEquals("Surname 4", client.secondName);


        Flux<MigrationMetadata> miFlux = Flux.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(connection.createStatement("select * from \"my scheme\".\"my migrations\" order by id").execute())
                .flatMap(o -> o.map((row, rowMetadata) -> {
                    return new MigrationMetadata(
                        row.get("id", Integer.class),
                        row.get("description", String.class),
                        false,
                        false,
                        false,
                        false
                    );
                })),
            Connection::close
        );
        List<MigrationMetadata> migrationInfos = miFlux.collectList().block();
        Assertions.assertFalse(migrationInfos.isEmpty());
        Assertions.assertEquals("create customers", migrationInfos.get(0).getDescription());

        Mono<Boolean> r = Mono.usingWhen(
            makeConnectionMono(mappedPort).create(),
            connection -> Mono.from(connection.createStatement("select locked from \"my scheme\".\"my migrations lock\" where id = 1").execute())
                .flatMap(o -> Mono.from(o.map(getResultSafely("locked", Boolean.class, null)))),
            Connection::close);
        Boolean block = r.block();
        Assertions.assertNotNull(block);
        Assertions.assertFalse(block);
    }

    @Test
    public void testOtherMigrationSchemaPremigration() {
        R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
        properties.setMigrationsSchema("my premigrable scheme");
        properties.setMigrationsTable("my migrations");
        properties.setMigrationsLockTable("my migrations lock");
        properties.setResourcesPath("classpath:/migrations/postgresql_premigration/*.sql");
        properties.setPreferDbSpecificLock(false);
        Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
        ConnectionFactory connectionFactory = makeConnectionMono(mappedPort);

        R2dbcMigrate.migrate(connectionFactory, properties, springResourceReader, null, null).block();

        Flux<Customer> clientFlux = Flux.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(connection.createStatement("select * from customer order by id").execute())
                .flatMap(o -> o.map((row, rowMetadata) -> {
                    return new Customer(
                        row.get("first_name", String.class),
                        row.get("last_name", String.class),
                        row.get("id", Integer.class)
                    );
                })),
            Connection::close
        );
        Customer client = clientFlux.blockLast();

        Assertions.assertEquals("Customer", client.firstName);
        Assertions.assertEquals("Surname 4", client.secondName);


        Flux<MigrationMetadata> miFlux = Flux.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(connection.createStatement("select * from \"my premigrable scheme\".\"my migrations\" order by id").execute())
                .flatMap(o -> o.map((row, rowMetadata) -> {
                    return new MigrationMetadata(
                        row.get("id", Integer.class),
                        row.get("description", String.class),
                        false,
                        false,
                        false,
                        false
                    );
                })),
            Connection::close
        );
        List<MigrationMetadata> migrationInfos = miFlux.collectList().block();
        Assertions.assertFalse(migrationInfos.isEmpty());
        Assertions.assertEquals("create customers", migrationInfos.get(0).getDescription());

        Mono<Boolean> r = Mono.usingWhen(
            makeConnectionMono(mappedPort).create(),
            connection -> Mono.from(connection.createStatement("select locked from \"my premigrable scheme\".\"my migrations lock\" where id = 1").execute())
                .flatMap(o -> Mono.from(o.map(getResultSafely("locked", Boolean.class, null)))),
            Connection::close);
        Boolean block = r.block();
        Assertions.assertNotNull(block);
        Assertions.assertFalse(block);
    }

    @Test
    public void testWithReflections() {
        R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
        properties.setResourcesPath("migrations/postgresql/");
        Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
        ConnectionFactory connectionFactory = makeConnectionMono(mappedPort);

        ReflectionsClasspathResourceReader reflectionsClasspathResourceReader = new ReflectionsClasspathResourceReader();

        R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, reflectionsClasspathResourceReader, null, null).block();

        Flux<Customer> clientFlux = Flux.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(connection.createStatement("select * from customer order by id").execute())
                .flatMap(o -> o.map((row, rowMetadata) -> {
                    return new Customer(
                        row.get("first_name", String.class),
                        row.get("last_name", String.class),
                        row.get("id", Integer.class)
                    );
                })),
            Connection::close
        );
        Customer client = clientFlux.blockLast();

        Assertions.assertEquals("Customer", client.firstName);
        Assertions.assertEquals("Surname 4", client.secondName);
    }

    static class SimplePostgresqlDialect implements SqlQueries {
        @Override
        public List<String> createInternalTables() {
            return List.of(
                "create table if not exists simple_migrations(id bigint primary key, description text)"
            );
        }

        @Override
        public String getMaxMigration() {
            return "select max(id) from simple_migrations";
        }

        public String insertMigration() {
            return "insert into simple_migrations(id, description) values ($1, $2)";
        }

        @Override
        public Statement createInsertMigrationStatement(Connection connection, MigrationMetadata migrationInfo) {
            return connection
                .createStatement(insertMigration())
                .bind("$1", migrationInfo.getVersion())
                .bind("$2", migrationInfo.getDescription());
        }

    }

    static class SimplePostgresqlLocker extends AbstractTableLocker implements Locker {

        public List<String> createInternalTables() {
            return List.of(
                "create table if not exists simple_migrations_lock(id int primary key, locked boolean not null)",
                "insert into simple_migrations_lock(id, locked) values (1, false) on conflict (id) do nothing"
            );
        }

        @Override
        public io.r2dbc.spi.Statement tryAcquireLock(Connection connection) {
            return connection.createStatement("update simple_migrations_lock set locked = true where id = 1 and locked = false");
        }

        @Override
        public io.r2dbc.spi.Statement releaseLock(Connection connection) {
            return connection.createStatement("update simple_migrations_lock set locked = false where id = 1");
        }

    }

    @Test
    public void testCustomDialect() {
        // create and start a ListAppender
        try (LogCaptor logCaptor = LogCaptor.forName(POSTGRES_QUERY_LOGGER)) {
            logCaptor.setLogLevelToDebug();

            R2dbcMigrateProperties properties = new R2dbcMigrateProperties();
            properties.setDialect(Dialect.POSTGRESQL);
            properties.setResourcesPath("classpath:/migrations/postgresql/*.sql");

            Integer mappedPort = container.getMappedPort(POSTGRESQL_PORT);
            R2dbcMigrate.migrate(makeConnectionMono(mappedPort), properties, springResourceReader, new SimplePostgresqlDialect(), new SimplePostgresqlLocker()).block();

            // make asserts
            assertTrue(
                hasSubList(logCaptor.getDebugLogs(), Arrays.asList(
                    "BEGIN",
                    "create table if not exists simple_migrations(id bigint primary key, description text); create table if not exists simple_migrations_lock(id int primary key, locked boolean not null); insert into simple_migrations_lock(id, locked) values (1, false) on conflict (id) do nothing",
                    "COMMIT",
                    "BEGIN",
                    "update simple_migrations_lock set locked = true where id = 1 and locked = false",
                    "COMMIT",
                    "select max(id) from simple_migrations",
                    "BEGIN",
                    "CREATE TABLE customer (id SERIAL PRIMARY KEY, first_name VARCHAR(255), last_name VARCHAR(255))",
                    "COMMIT",
                    "BEGIN",
                    "insert into simple_migrations(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "insert into customer(first_name, last_name) values ('Muhammad', 'Ali'), ('Name', 'Фамилия');",
                    "COMMIT",
                    "BEGIN",
                    "insert into simple_migrations(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "insert into customer(first_name, last_name) values ('Customer', 'Surname 1');; insert into customer(first_name, last_name) values ('Customer', 'Surname 2');; insert into customer(first_name, last_name) values ('Customer', 'Surname 3');; insert into customer(first_name, last_name) values ('Customer', 'Surname 4');",
                    "COMMIT",
                    "BEGIN",
                    "insert into simple_migrations(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "create table example(id serial primary key);\n",
                    "COMMIT",
                    "BEGIN",
                    "insert into simple_migrations(id, description) values ($1, $2)",
                    "COMMIT",
                    "BEGIN",
                    "update simple_migrations_lock set locked = false where id = 1",
                    "COMMIT"
                )));
            logCaptor.setLogLevelToInfo();
        }
    }

    private static boolean hasSubList(List<String> collect, List<String> sublist) {
        sublist = sublist.stream().map(s -> "Executing query: " + s).collect(Collectors.toList());
        return (Collections.indexOfSubList(collect, sublist) != -1);
    }
}
