package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/** No MySQL needed: points at a port where nothing is listening. */
class WalletServiceConnectionFailureTest {

    @Test
    void unreachableDatabaseGivesFriendlyMessageWithoutConnectionDetails() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        WalletService service = new WalletService(new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + freePort + "/lamma?connectTimeout=2000", "lamma_app", "pw")));

        DatabaseException e = assertThrows(DatabaseException.class, () -> service.loadPersonalWallet(1));

        assertEquals(WalletService.UNAVAILABLE_MESSAGE, e.getMessage());
        assertFalse(e.getMessage().contains("jdbc:"));
        assertFalse(e.getMessage().contains(String.valueOf(freePort)));
    }
}
