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
class TransferServiceConnectionFailureTest {

    @Test
    void outageGivesPlainMessages() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        TransferService service = new TransferService(new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + port + "/lamma?connectTimeout=2000", "u", "p")));

        assertEquals(TransferService.LOOKUP_FAILED_MESSAGE,
                assertThrows(DatabaseException.class, () -> service.findRecipient(1, "70 123 456")).getMessage());
        assertEquals(TransferService.FAILED_MESSAGE,
                assertThrows(DatabaseException.class, () -> service.send(1, 2, new BigDecimal("5.00"), "r")).getMessage());
    }
}
