package com.lamma.testsupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.SchemaMigrator;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Shared setup for MySQL integration tests. Skips the calling test unless
 * LAMMA_TEST_DB_URL, LAMMA_TEST_DB_USER and LAMMA_TEST_DB_PASSWORD are set, and refuses
 * to touch a database whose name does not end in "_test".
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    /** Drops all LAMMA tables, re-runs every migration, and returns the database. */
    public static Database freshSchema() throws Exception {
        String url = System.getenv("LAMMA_TEST_DB_URL");
        String user = System.getenv("LAMMA_TEST_DB_USER");
        String password = System.getenv("LAMMA_TEST_DB_PASSWORD");
        assumeTrue(url != null && user != null && password != null,
                "Set LAMMA_TEST_DB_URL / LAMMA_TEST_DB_USER / LAMMA_TEST_DB_PASSWORD to run MySQL tests");

        Database database = new Database(new DatabaseConfig(url, user, password));
        try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
            if (!conn.getCatalog().endsWith("_test")) {
                throw new IllegalStateException("Refusing to wipe non-test database: " + conn.getCatalog());
            }
            st.execute("DROP TABLE IF EXISTS wallet_transactions");
            st.execute("DROP TABLE IF EXISTS payment_cards");
            st.execute("DROP TABLE IF EXISTS wallets");
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("DROP TABLE IF EXISTS schema_version");
        }
        new SchemaMigrator(database).migrate();
        return database;
    }
}
