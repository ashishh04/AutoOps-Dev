package com.intertec.autoops.agent;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * A real MySQL 8.4 for an integration test, from whichever source is available.
 *
 * <p><b>Why this exists.</b> Testcontainers cannot start a container from inside
 * the Maven container on this Windows workstation: Docker Desktop's socket proxy
 * answers docker-java's {@code /info} with a 400 and a stub body pointing at
 * {@code npipe://./pipe/docker_cli}, which a Linux container cannot reach. The
 * socket itself is fine — {@code curl} and the {@code docker} CLI both use it
 * happily through the same mount — so it is specific to Testcontainers' daemon
 * discovery, and not something this project can fix.
 *
 * <p>The cost of leaving it was the real problem. Every migration from V6 to V9
 * was validated by hand against a clone of the live schema: careful, repeatable,
 * and dependent on somebody choosing to do it. That is an acceptable trade for a
 * store that fails loudly. It is the wrong one for the reaper, which is the first
 * component whose bugs <i>delete state</i> — a missed invariant there does not
 * throw, it quietly empties a backlog.
 *
 * <p>So the ITs no longer ask Testcontainers to find a daemon. They ask for a
 * database, and take it from whichever source exists:
 *
 * <ul>
 *   <li><b>Handed to us</b> — {@code AUTOOPS_TEST_MYSQL_URL} points at a running
 *       server. A throwaway schema is created per run and dropped after. This is
 *       what {@code run-its.sh} sets up locally.</li>
 *   <li><b>Testcontainers</b> — the fallback, and what CI uses. Unchanged
 *       behaviour on Linux, where daemon discovery works.</li>
 * </ul>
 *
 * <p>Both paths run Flyway to head against a genuinely empty schema, so the
 * assertions are identical either way and neither is a lesser test.
 */
public final class MySqlTestDatabase implements AutoCloseable {

    /** Pinned to the deployed major.minor; a floating tag reintroduces the drift. */
    private static final String IMAGE = "mysql:8.4";

    private static final String URL_ENV = "AUTOOPS_TEST_MYSQL_URL";
    private static final String USER_ENV = "AUTOOPS_TEST_MYSQL_USER";
    private static final String PASSWORD_ENV = "AUTOOPS_TEST_MYSQL_PASSWORD";

    private final MySQLContainer<?> container;
    private final Connection connection;
    private final String schema;
    private final String serverUrl;
    private final String user;
    private final String password;
    private final String mode;

    private MySqlTestDatabase(MySQLContainer<?> container, Connection connection, String schema,
                              String serverUrl, String user, String password, String mode) {
        this.container = container;
        this.connection = connection;
        this.schema = schema;
        this.serverUrl = serverUrl;
        this.user = user;
        this.password = password;
        this.mode = mode;
    }

    public static MySqlTestDatabase start() throws Exception {
        String serverUrl = System.getenv(URL_ENV);
        return serverUrl == null || serverUrl.isBlank()
                ? fromTestcontainers()
                : fromServer(serverUrl);
    }

    private static MySqlTestDatabase fromTestcontainers() throws Exception {
        MySQLContainer<?> container =
                new MySQLContainer<>(IMAGE).withDatabaseName("autoops_agent");
        container.start();
        Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        return new MySqlTestDatabase(container, connection, null,
                container.getJdbcUrl(), container.getUsername(), container.getPassword(),
                "testcontainers " + IMAGE);
    }

    /**
     * A throwaway schema on a server somebody else started.
     *
     * <p>The name is unique per run so two suites can share one server without
     * one dropping the other's schema out from under it.
     */
    private static MySqlTestDatabase fromServer(String serverUrl) throws Exception {
        String user = System.getenv().getOrDefault(USER_ENV, "root");
        String password = System.getenv().getOrDefault(PASSWORD_ENV, "");
        String schema = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        try (Connection admin = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + schema
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        Connection connection = DriverManager.getConnection(
                jdbcFor(serverUrl, schema), user, password);
        return new MySqlTestDatabase(null, connection, schema,
                serverUrl, user, password, "server " + redact(serverUrl) + " schema " + schema);
    }

    /** Runs every migration in order, exactly as a deployment would. */
    void migrate() {
        Flyway.configure()
                .dataSource(schema == null ? serverUrl : jdbcFor(serverUrl, schema), user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    Connection connection() {
        return connection;
    }

    /** The JDBC URL for the schema this run owns, for Spring to bind to. */
    public String url() {
        return schema == null ? serverUrl : jdbcFor(serverUrl, schema);
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** Printed on failure, so a red build says which database it was talking to. */
    String mode() {
        return mode;
    }

    @Override
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        if (container != null) {
            container.stop();
            return;
        }
        // A schema left behind would accumulate one per run on a shared server.
        try (Connection admin = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + schema);
        }
    }

    private static String jdbcFor(String serverUrl, String schema) {
        String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        int query = base.indexOf('?');
        return query < 0
                ? base + schema
                : base.substring(0, query) + schema + base.substring(query);
    }

    /** Never print a URL that might carry credentials in its query string. */
    private static String redact(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
