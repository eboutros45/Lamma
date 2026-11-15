package com.lamma.db;

/**
 * Command-line database setup and connection check.
 *
 * <pre>
 *     mvn -q compile exec:java
 * </pre>
 *
 * Creates or updates the schema in the configured database and prints the result.
 * Exits with status 1 and a readable message if MySQL is unavailable.
 */
public final class DatabaseSetup {

    private DatabaseSetup() {
    }

    public static void main(String[] args) {
        try {
            DatabaseStatus.Report report = DatabaseStatus.checkAndMigrate();
            System.out.println("OK: " + report.summary());
        } catch (DatabaseException e) {
            System.err.println("DATABASE ERROR: " + e.getMessage());
            System.exit(1);
        }
    }
}
