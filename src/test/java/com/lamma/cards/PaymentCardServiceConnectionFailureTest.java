package com.lamma.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lamma.db.Database;
import com.lamma.db.DatabaseConfig;
import com.lamma.db.DatabaseException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/** No MySQL needed: points at a port where nothing is listening. */
class PaymentCardServiceConnectionFailureTest {

    @Test
    void outageGivesPlainMessage() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        PaymentCardService service = new PaymentCardService(new Database(new DatabaseConfig(
                "jdbc:mysql://127.0.0.1:" + port + "/lamma?connectTimeout=2000", "u", "p")));

        DatabaseException e = assertThrows(DatabaseException.class, () -> service.listCards(1));
        assertEquals(PaymentCardService.UNAVAILABLE_MESSAGE, e.getMessage());
    }
}
