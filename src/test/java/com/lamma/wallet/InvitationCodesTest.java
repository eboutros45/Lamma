package com.lamma.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.ValidationException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvitationCodesTest {

    @Test
    void codesAreRandomOpaqueAndUrlSafe() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = InvitationCodes.newCode();
            assertTrue(code.matches("[A-Za-z0-9_-]{24}"), code);
            codes.add(code);
        }
        assertEquals(1000, codes.size(), "no repeats");
        assertNotEquals(InvitationCodes.newCode(), InvitationCodes.newCode());
    }

    @Test
    void parseAcceptsLinkOrBareCode() throws ValidationException {
        String code = "AbCdEfGhIjKlMnOpQrStUv_-";
        assertEquals("lamma://join/" + code, InvitationCodes.payload(code));
        assertEquals(code, InvitationCodes.parse(code));
        assertEquals(code, InvitationCodes.parse("  lamma://join/" + code + "  "));
        assertEquals(code, InvitationCodes.parse("LAMMA://JOIN/" + code));
    }

    @Test
    void parseRejectsAnythingElse() {
        for (String bad : new String[] {"", "  ", "lamma://join/", "12345", "lamma://pay/AbCdEfGhIjKlMnOpQrStUv_-",
                "AbCdEfGhIjKlMnOpQrStUv_-x", "AbCdEfGhIjKlMnOpQrStU!_-", "' OR 1=1 --"}) {
            assertThrows(ValidationException.class, () -> InvitationCodes.parse(bad), bad);
        }
        assertThrows(ValidationException.class, () -> InvitationCodes.parse(null));
    }
}
