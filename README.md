# PostgreSQL DataSource Integration Test Helper

A helper class for PostgreSQL integration testing.

It allows for postgresql connections made from

* System properties (give name of property that defines the port and the name of the database)
* Environment Variables (give name of environment variable, that contain a connect URI)
* fallback
    * from PG* Environment variables
    * from ${user.name} (as user, password and database)

It also implements a number of helper methods, to manipulate the database.

Functions to:

* wipe a schema
* dump/truncate and restore tables either by name from a list or all tables in foreign key respecting order

## Usage

A typical use case is outlined below:

### Typical Test Environment

        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <version>${some.version}</version>
            <configuration>
                <redirectTestOutputToFile>false</redirectTestOutputToFile>
                <systemPropertyVariables>
                    <postgresql.testbase.port>${postgresql.testbase.port}</postgresql.testbase.port>
                    <postgresql.dump.folder>${postgresql.dump.folder}</postgresql.dump.folder>
                </systemPropertyVariables>
                <argLine>-Dfile.encoding=${project.build.sourceEncoding}</argLine>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>integration-test</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>

        <plugin>
            <groupId>dk.kosmisk</groupId>
            <artifactId>postgresql-maven-plugin</artifactId>
            <version>${some.version}</version>
            <configuration>
                <!-- <version>LATEST</version> -->
            </configuration>
            <executions>
                <execution>
                    <id>postgresql-test-database</id>
                    <goals>
                        <goal>setup</goal>
                        <goal>startup</goal>
                        <goal>shutdown</goal>
                    </goals>
                    <configuration>
                        <name>testbase</name>
                        <scripts>
                            <script>${basedir}/src/test/resources/schema.sql</script>
                            <script>${basedir}/src/test/resources/testdata.sql</script>
                        </scripts>
                    </configuration>
                </execution>
            </executions>
        </plugin>

...

        <dependency>
            <groupId>dk.kosmisk</groupId>
            <artifactId>postgresql-test-datasource</artifactId>
            <version>${some.version}</version>
            <type>jar</type>
        </dependency>


### Typical Test

        public class EntityTest {

            private PostgresITDataSource dataSource;

            @Before
            public void setup() throws Exception {
                dataSource = PostgresITDataSource.builder()
                        .fromProperty("testbase", "postgresql.testbase.port")
                        .fromEnvironment("MY_PGTEST_URL")
                        .withFallback()
                        .build();
               dataSource.copyAllTablesToDisk();
            }

            @After
            public void cleanup() throws Exception {
                dataSource.truncateAllTables();
                dataSource.copyAllTablesFromDisk();
            }

            @Test
            public void testSomething() throws Exception {
                System.out.println("Something");
                try(Connection connection = dataSource.getConnection()) {

...

                }
            }

