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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 *
 * @author Source (source (at) kosmisk.dk)
 */
public class PostgresITDataSourceIT {

    PostgresITDataSource dataSource;

    public PostgresITDataSourceIT() throws ClassNotFoundException, SQLException {
        dataSource = PostgresITDataSource.builder()
                .fromProperty("testbase")
                .fromEnvironment("LOCAL_POSTGRESQL_URL")
                .build();
    }

    @Test
    public void testGetConnection() throws Exception {
        System.out.println("getConnection");
        try (Connection connection = dataSource.getConnection() ;
             Statement stmt = connection.createStatement() ;
             ResultSet resultSet = stmt.executeQuery("select version()")) {
            if (resultSet.next()) {
                String version = resultSet.getString(1);
                boxOutput(version);
                return;
            }
        }
        fail("Could not get version from database");
    }

    @Test
    public void testAllTableNames() throws Exception {
        System.out.println("allTableNames");
        List<String> tables = dataSource.allTableNames();
        System.out.println("allTableNames = " + tables);
        assertEquals(3, tables.size());
        String tableOrder = String.join(", ", tables);
        assertEquals("foo, bar, fin", tableOrder);
    }

    @Test
    public void testCopyTablesToAndFromDisk() throws Exception {
        System.out.println("copyTablesToAndFromDisk");
        dataSource.truncateAllTables();
        try (Connection connection = dataSource.getConnection() ;
             PreparedStatement foo = connection.prepareStatement("INSERT INTO foo VALUES(?)") ;
             PreparedStatement bar = connection.prepareStatement("INSERT INTO bar VALUES(?, ?)")) {
            connection.setAutoCommit(false);
            for (String string : "a,b,c".split(",")) {
                foo.setString(1, string);
                foo.executeUpdate();
            }
            for (String string : "1=a,2=a,3=b".split(",")) {
                bar.setString(1, string.split("=", 2)[0]);
                bar.setString(2, string.split("=", 2)[1]);
                bar.executeUpdate();
            }
            connection.commit();
        }
        testRowCount(3, 3);
        dataSource.copyAllTablesToDisk();
        dataSource.truncateAllTables();
        testRowCount(0, 0);
        dataSource.copyAllTablesFromDisk();
        testRowCount(3, 3);
        dataSource.truncateAllTables();
    }

    private void testRowCount(int foo, int bar) throws SQLException {
        try (Connection connection = dataSource.getConnection() ;
             Statement fooStmt = connection.createStatement() ;
             ResultSet fooResult = fooStmt.executeQuery("SELECT COUNT(*) FROM foo") ;
             Statement barStmt = connection.createStatement() ;
             ResultSet barResult = barStmt.executeQuery("SELECT COUNT(*) FROM bar")) {
            fooResult.next();
            assertEquals(foo, fooResult.getInt(1));
            barResult.next();
            assertEquals(bar, barResult.getInt(1));
        }
    }

    private void boxOutput(String text) {
        String line = "# " + text + " #";
        String band = line.replaceAll("[^#]", "#");
        System.out.println(band);
        System.out.println(line);
        System.out.println(band);
    }

}
