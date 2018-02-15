/*
 * Copyright (C) 2018 Source (source (at) kosmisk.dk)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.kosmisk.postgresql.it;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.dbcp2.ConnectionFactory;
import org.apache.commons.dbcp2.DriverManagerConnectionFactory;
import org.apache.commons.dbcp2.PoolableConnection;
import org.apache.commons.dbcp2.PoolableConnectionFactory;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;

/**
 * A pooling datasource for integration testing with PostgreSQL
 *
 *
 * @author Source (source (at) kosmisk.dk)
 */
public class PostgresITDataSource extends PoolingDataSource<PoolableConnection> {

    private static final String DRIVER = "org.postgresql.Driver";

    /**
     * Construct a PostgreSQL datasource from the first available location
     *
     * @param locations   list of locations {@link DatabaseLocation} to search
     *                    through
     * @param useFallback if no database could be found, should we try the PG*
     *                    environment variables and fall back to ${user.name}
     */
    public PostgresITDataSource(List<DatabaseLocation> locations, boolean useFallback) {
        super(makeConnectionPool(locations, useFallback));
    }

    /**
     * Construct a PostgreSQL datasource from the first available location
     * <p>
     * convenience constructor for
     * {@link PostgresITDataSource#PostgresITDataSource(java.util.List, boolean)}
     * with useFallback set to true
     *
     * @param location see
     *                 {@link PostgresITDataSource#PostgresITDataSource(java.util.List, boolean)}
     */
    public PostgresITDataSource(DatabaseLocation location) {
        this(Arrays.asList(location), true);
    }

    /**
     * Construct a PostgreSQL datasource from the system properties or fall back
     * to system database
     *
     * @param databaseName     name of database
     * @param portPropertyName name of property containing port number
     */
    public PostgresITDataSource(String databaseName, String portPropertyName) {
        this(Arrays.asList(new DatabaseFromProperty(databaseName, portPropertyName)), true);
    }

    /**
     * Construct a PostgreSQL datasource from the system properties or fall back
     * to system database
     *
     * @param databaseName name of database, and find port from system property
     *                     "postgresql.${name}.port"
     */
    public PostgresITDataSource(String databaseName) {
        this(Arrays.asList(new DatabaseFromProperty(databaseName)), true);
    }

    /**
     * Set database logging to all
     *
     * @param connection The connection to use and return
     * @return the connection supplied as argument
     * @throws SQLException if we're unable to enable logging
     */
    private static Connection setLogging(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("SET log_statement = 'all'");
        }
        return connection;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return setLogging(super.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return setLogging(super.getConnection(user, password));
    }

    /**
     * Truncate database tables
     * <p>
     * This runs in a single transaction, so if one fails (table listed, but
     * doesn't exist), all tables retain their content.
     *
     * @param tables list of table names
     * @throws SQLException if tables doesn't exist
     */
    @SuppressFBWarnings("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE")
    public void truncateTables(Collection<String> tables) throws SQLException {
        try (Connection connection = super.getConnection();
             Statement stmt = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (String table : tables) {
                table = table.replaceAll("[^0-9_a-zA-Z]", "");
                stmt.execute("TRUNCATE " + table + " CASCADE");
            }
            connection.commit();
        }
    }

    /**
     * Convenience method for {@link #truncateTables(java.util.Collection)}
     *
     * @param tables list of table names
     * @throws SQLException if tables doesn't exist
     */
    public void truncateTables(String... tables) throws SQLException {
        truncateTables(Arrays.asList(tables));
    }

    /**
     * Convenience method for {@link #truncateTables(java.util.Collection)}
     * <p>
     * Takes list of tables from {@link #allTableNames()}
     *
     * @throws SQLException if tables doesn't exist - This really shouldn't
     *                      happen
     */
    public void truncateAllTables() throws SQLException {
        truncateTables(allTableNames());
    }

    /**
     * Drop and create public schema.
     *
     * This is the fastest way to empty a database using schemaname "public"
     *
     * @throws SQLException if there's problems dropping schema "public"
     */
    public void wipe() throws SQLException {
        try (Connection connection = super.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DROP SCHEMA IF EXISTS public CASCADE");
            stmt.executeUpdate("CREATE SCHEMA public");
        }
    }

    private static final String ALL_TABLES
            = "SELECT tablename" +
              " FROM pg_tables" +
              " WHERE schemaname='public'";
    private static final String FOREIGN_KEY
            = "SELECT ft.relname, tt.relname" +
              " FROM pg_constraint AS c" +
              " JOIN pg_namespace AS n ON c.connamespace = n.oid" +
              " JOIN pg_class AS ft ON c.conrelid = ft.oid" +
              " JOIN pg_class AS tt ON c.confrelid = tt.oid" +
              " WHERE n.nspname = 'public' AND c.contype = 'f'";

    /**
     * List all tables in schema public
     * <p>
     * This list is ordered in a way so tables with foreign keys comes after the
     * tables they refer.
     * If mutual references exists a RuntimeExcepttion is thrown
     *
     * @return ordered list of table names
     * @throws SQLException if lists cannot be made
     */
    public List<String> allTableNames() throws SQLException {
        HashMap<String, HashSet<String>> foreignKeysRules = new HashMap<>();
        try (Connection connection = super.getConnection();
             Statement tablesStmt = connection.createStatement();
             Statement foreignKeysStmt = connection.createStatement();
             ResultSet tables = tablesStmt.executeQuery(ALL_TABLES);
             ResultSet foreignKeys = foreignKeysStmt.executeQuery(FOREIGN_KEY)) {
            while (tables.next()) {
                foreignKeysRules.put(tables.getString(1), new HashSet<>());
            }
            while (foreignKeys.next()) {
                foreignKeysRules.get(foreignKeys.getString(1))
                        .add(foreignKeys.getString(2));
            }
        }
        ArrayList<String> orderedTables = new ArrayList<>(foreignKeysRules.size());
        while (!foreignKeysRules.isEmpty()) {
            Set<String> tables = foreignKeysRules.entrySet().stream()
                    .filter(e -> e.getValue().isEmpty())
                    .map(e -> e.getKey())
                    .collect(Collectors.toSet());
            if (tables.isEmpty()) {
                throw new IllegalStateException("Tables have mutual foreign key. No order can be determined for: " + String.join(", ", foreignKeysRules.keySet()));
            }
            foreignKeysRules.keySet().removeAll(tables);
            foreignKeysRules.values()
                    .stream()
                    .forEach(set -> set.removeAll(tables));
            orderedTables.addAll(tables);
        }
        return orderedTables;
    }

    /**
     * Ask the database to copy all the content of listed tables to disk
     *
     * @param tables list of table names
     * @throws SQLException if the database cannot copy table content
     */
    public void copyTablesToDisk(Collection<String> tables) throws SQLException {
        copyData(tables, "TO");
    }

    /**
     * Convenience method for {@link #copyTablesToDisk(java.util.Collection)}
     *
     * @param tables list of table names
     * @throws SQLException if the database cannot copy table content
     */
    public void copyTablesToDisk(String... tables) throws SQLException {
        copyTablesToDisk(Arrays.asList(tables));
    }

    /**
     * Convenience method for {@link #copyTablesToDisk(java.util.Collection)}
     * <p>
     * Takes list of tables from {@link #allTableNames()}
     *
     * @throws SQLException if the database cannot copy table content
     */
    public void copyAllTablesToDisk() throws SQLException {
        copyTablesToDisk(allTableNames());
    }

    /**
     * Ask the database to copy all the content backup files into the tables
     *
     * @param tables list of table names
     * @throws SQLException if the database cannot copy table content
     */
    public void copyTablesFromDisk(Collection<String> tables) throws SQLException {
        copyData(tables, "FROM");
    }

    /**
     * Convenience method for {@link #copyTablesFromDisk(java.util.Collection)}
     *
     * @param tables list of table names
     * @throws SQLException if the database cannot copy table content
     */
    public void copyTablesFromDisk(String... tables) throws SQLException {
        copyTablesFromDisk(Arrays.asList(tables));
    }

    /**
     * Convenience method for {@link #copyTablesFromDisk(java.util.Collection)}
     * <p>
     * Takes list of tables from {@link #allTableNames()}
     *
     * @throws SQLException if the database cannot copy table content
     */
    public void copyAllTablesFromDisk() throws SQLException {
        copyTablesFromDisk(allTableNames());
    }

    @SuppressFBWarnings("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE")
    private void copyData(Collection<String> tables, String direction) throws SQLException {
        String dumpFolderLocation = System.getProperty("postgresql.dump.folder");
        if (dumpFolderLocation == null) {
            dumpFolderLocation = System.getProperty("java.io.tmpdir");
            if (dumpFolderLocation != null) {
                File tempDirFile = new File(dumpFolderLocation).toPath()
                        .resolve("pg_dumps")
                        .toFile();
                if (!tempDirFile.isDirectory() &&
                    !tempDirFile.mkdirs()) {
                    throw new RuntimeException("Could not make temp dir for postgres dumps: " + tempDirFile.toString());
                }
                dumpFolderLocation = tempDirFile.toString();
            }
        }
        if (dumpFolderLocation == null) {
            throw new RuntimeException("Cannot find temp location for postgres dumps");
        }

        Path tempPath = new File(dumpFolderLocation).toPath();
        try (Connection connection = super.getConnection();
             Statement stmt = connection.createStatement()) {
            for (String table : tables) {
                table = table.replaceAll("[^0-9_a-zA-Z]", "");
                StringBuilder sql = new StringBuilder();
                sql.append("COPY ")
                        .append(table)
                        .append(" ")
                        .append(direction)
                        .append(" '")
                        .append(tempPath.resolve(table + ".dat").toString()
                                .replaceAll("'", "''"))
                        .append("'");
                stmt.executeUpdate(sql.toString());
            }
        }
    }

    private static ObjectPool<PoolableConnection> makeConnectionPool(List<DatabaseLocation> locations, boolean useFallback) {
        String connectString = null;
        Properties props = new Properties();
        for (DatabaseLocation location : locations) {
            connectString = location.jdbcUrl(props);
            if (connectString != null) {
                break;
            }
        }
        if (connectString == null && useFallback) {
            connectString = DATABASE_FALLBACK.jdbcUrl(props);
        }
        if (connectString == null) {
            throw new IllegalStateException("Cannot locate database");
        }
        return constructConnectionPool(connectString, props);
    }

    private static ObjectPool<PoolableConnection> constructConnectionPool(String connectString, Properties props) {
        try {
            PostgresITDataSource.class.getClassLoader().loadClass(DRIVER);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException("Cannot load driver: " + DRIVER, ex);
        }
        ConnectionFactory factory = new DriverManagerConnectionFactory(connectString, props);
        PoolableConnectionFactory pool = new PoolableConnectionFactory(factory, null);
        ObjectPool<PoolableConnection> connectionPool = new GenericObjectPool<>(pool);
        pool.setPool(connectionPool);
        return connectionPool;
    }

    /**
     * Construct a default builder
     *
     * @return new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder pattern for a {@link PostgresITDataSource}
     */
    public final static class Builder {

        private final List<DatabaseLocation> locations;
        private Boolean useFallback;

        public Builder() {
            locations = new ArrayList<>();
            this.useFallback = null;
        }

        /**
         * Set database name and port property
         * <p>
         * Remember to export port using the maven-failsafe-plugin plugin
         *
         * @param databaseName     name of database
         * @param portPropertyName name of system property containing port
         * @return self
         */
        public Builder fromProperty(String databaseName, String portPropertyName) {
            locations.add(new DatabaseFromProperty(databaseName, portPropertyName));
            return this;
        }

        /**
         * Set database name, take port from postgresql.${name}.port property
         * <p>
         * Remember to export port using the maven-failsafe-plugin plugin
         *
         * @param databaseName name of database
         * @return self
         */
        public Builder fromProperty(String databaseName) {
            locations.add(new DatabaseFromProperty(databaseName));
            return this;
        }

        /**
         * Search environment variable for database location
         * <p>
         * Environment variable should contain a uri with:
         * <p>
         * <ul>
         * <li> no schema or schema 'postgres' or 'postgresql'
         * <li> user
         * <li> password
         * <li> hostname
         * <li> port (optional defaults to 5432)
         * <li> database
         * </ul>
         *
         * @param environmentName name if environment variable to examine
         * @return self
         */
        public Builder fromEnvironment(String environmentName) {
            locations.add(new DatabaseFromEnvironment(environmentName));
            return this;
        }

        /**
         * Disallow fallback definition
         *
         * @return self
         */
        public Builder withoutFallback() {
            this.useFallback = setOrFail(this.useFallback, false, "withoutFallback");
            return this;
        }

        /**
         * Allow use of $PG* environment variables for database discovery
         * <p>
         * <ul>
         * <li> user is $PGUSER or ${user.name}
         * <li> password is $PGPASSWORD or ${user.name}
         * <li> host is $PGHOST or 127.0.0.1
         * <li> port is $PGPORT or 5432
         * <li> database is $PGDATABASE or ${user.name}
         * </ul>
         *
         * @return self
         */
        public Builder withFallback() {
            this.useFallback = setOrFail(this.useFallback, true, "withFallback");
            return this;
        }

        /**
         * Build a DataSource
         *
         * @return new dataSource
         */
        public PostgresITDataSource build() {
            return new PostgresITDataSource(locations, or(null, useFallback, true));
        }

        private <T> T setOrFail(T oldValue, T newValue, String name) {
            if (oldValue != null) {
                throw new IllegalArgumentException("Cannot set " + name +
                                                   " to: " + newValue +
                                                   " has already been set to: " + oldValue);
            }
            return newValue;
        }

        @SafeVarargs
        @SuppressWarnings("FinalPrivateMethod")
        final private <T> T or(String name, T... ts) {
            for (T t : ts) {
                if (t != null) {
                    return t;
                }
            }
            throw new IllegalArgumentException("Required value has not been set " + name);
        }
    }

    /**
     * Interface describing a way to locate a database
     */
    public interface DatabaseLocation {

        /**
         * Construct a jdbc url
         *
         * Locate a database, and fill out user/password
         * If a database cannot be located then return null, and do not set any
         * user/password values
         *
         * @param props where user/password is set
         * @return jdbc url or null
         */
        String jdbcUrl(Properties props);
    }

    /**
     * Fallback location as described in {@link Builder#withFallback()}
     */
    private static DatabaseLocation DATABASE_FALLBACK = (props) -> {
        Map<String, String> env = System.getenv();
        String userName = System.getProperty("user.name");
        String user = env.getOrDefault("PGUSER", userName);
        String pass = env.getOrDefault("PGPASSWORD", userName);
        String host = env.getOrDefault("PGHOST", "localhost");
        String port = env.getOrDefault("PGPORT", "5432");
        String base = env.getOrDefault("PGDATABASE", userName);
        if (user != null) {
            props.setProperty("user", user);
        }
        if (pass != null) {
            props.setProperty("password", pass);
        }
        return "jdbc:postgresql://" + host + ":" + port + "/" + base;
    };

    /**
     * Database location from System Property, with user/password from
     * ${user.name}
     */
    public static class DatabaseFromProperty implements DatabaseLocation {

        private final String portProperty;
        private final String databaseName;

        /**
         * Look for database in properties
         *
         * @param databaseName name of database
         * @param portProperty system property containing port
         */
        public DatabaseFromProperty(String databaseName, String portProperty) {
            this.portProperty = portProperty;
            this.databaseName = databaseName;
        }

        /**
         * Convenience constructor for {@link #DatabaseFromProperty(java.lang.String, java.lang.String)
         * }
         * <p>
         * Passes through the databaseName, and constructs a system property as
         * given 'postgresql.${databaseName}.port'
         * <p>
         * This is the convention for matching ports and databases
         *
         * @param databaseName name of database
         */
        public DatabaseFromProperty(String databaseName) {
            this(databaseName, "postgresql." + databaseName + ".port");
        }

        @Override
        public String jdbcUrl(Properties props) {
            String propertyPort = System.getProperty(portProperty);
            if (propertyPort == null) {
                return null;
            }
            String userName = System.getProperty("user.name");
            props.setProperty("user", userName);
            props.setProperty("password", userName);
            return "jdbc:postgresql://localhost:" + propertyPort + "/" + databaseName;
        }
    }

    /**
     * Construct a {@link DatabaseLocation} from an environment variable
     */
    public static class DatabaseFromEnvironment implements DatabaseLocation {

        private static final Pattern POSTGRES_URL_REGEX = Pattern.compile("(?:postgres(?:ql)?://)?(?:([^:@]+)(?::([^@]*))@)?([^:/]+)(?:([1-9][0-9]*))?/(.+)");

        private final String environmentName;

        /**
         * Look for an environment variable with a database uri
         * <p>
         * see {@link Builder#fromEnvironment(java.lang.String) } for
         * description
         *
         * @param environmentName name of environment variable
         */
        public DatabaseFromEnvironment(String environmentName) {
            this.environmentName = environmentName;
        }

        @Override
        public String jdbcUrl(Properties props) {
            String url = System.getenv(environmentName);
            if (url == null) {
                return null;
            }
            Matcher matcher = POSTGRES_URL_REGEX.matcher(url);
            if (matcher.matches()) {
                String user = matcher.group(1);
                String pass = matcher.group(2);
                String host = matcher.group(3);
                String port = matcher.group(4);
                String base = matcher.group(5);
                if (user != null) {
                    props.setProperty("user", user);
                }
                if (pass != null) {
                    props.setProperty("password", pass);
                }
                if (port == null) {
                    port = "5432";
                }
                return "jdbc:postgresql://" + host + ":" + port + "/" + base;
            } else {
                System.out.println("noMatch");
                System.err.println("Cannot match environment url: " + url + " - falling back");
                return null;
            }
        }
    }

}
