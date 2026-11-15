package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/** No MySQL needed: points at a port where nothing is listening. */
class TopUpServiceConnectionFailureTest {

    @Test
    void outageGivesPlainMessage() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        TopUpService service = new TopUpService(new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + port + "/lamma?connectTimeout=2000", "u", "p")));

        DatabaseException e = assertThrows(DatabaseException.class,
                () -> service.cashTopUp(1, new BigDecimal("5.00"), "req-1"));
        assertEquals(TopUpService.FAILED_MESSAGE, e.getMessage());
    }
}
