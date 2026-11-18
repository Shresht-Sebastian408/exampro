package com.exampro.config.schema;

import com.exampro.config.ConnectionPool;
import com.exampro.exception.SchemaInitializationException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Drops every table and rebuilds from {@link SchemaDefinition}, then reseeds.
 *
 * Run it directly:
 * <pre>mvn -q exec:java -Dexec.mainClass=com.exampro.config.schema.ResetDatabase</pre>
 *
 * Constant use during development, and the safety net if the database gets
 * into a strange state mid-demo.
 */
public class ResetDatabase {

    public void run() {
        drop();
        new SchemaInitializer().initialize();
        boolean seeded = new DataSeeder().seedIfEmpty();
        System.out.println("Schema rebuilt" + (seeded ? " and reseeded." : " (already populated)."));
    }

    private void drop() {
        try (Connection conn = ConnectionPool.getConnection();
             Statement st = conn.createStatement()) {
            // Child-before-parent ordering would be enough on its own, but the
            // referential-integrity toggle also survives a stale table that the
            // drop list does not know about.
            st.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String table : SchemaDefinition.TABLE_NAMES_DROP_ORDER) {
                st.execute("DROP TABLE IF EXISTS " + table + " CASCADE");
            }
            st.execute("SET REFERENTIAL_INTEGRITY TRUE");
        } catch (SQLException e) {
            throw new SchemaInitializationException("Failed to drop the existing schema", e);
        }
    }

    public static void main(String[] args) {
        try {
            new ResetDatabase().run();
        } finally {
            ConnectionPool.shutdown();
        }
    }
}
