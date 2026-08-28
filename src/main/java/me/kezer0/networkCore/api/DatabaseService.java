package me.kezer0.networkCore.api;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared PostgreSQL infrastructure owned by NetworkCore.
 * Domain plugins may use this service for their own repositories/tables.
 */
public interface DatabaseService {
    boolean isAvailable();
    Connection getConnection() throws SQLException;
}
