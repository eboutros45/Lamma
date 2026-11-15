package com.lamma.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.auth.PinHasher;
import com.lamma.auth.RegistrationService;
import com.lamma.auth.ValidationException;
import com.lamma.contacts.ContactService.Contact;
import com.lamma.contacts.ContactService.ImportReport;
import com.lamma.db.Database;
import com.lamma.testsupport.TestDatabase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The contact book against real MySQL (skipped without LAMMA_TEST_DB_*). */
class ContactServiceIntegrationTest {

    private ContactService contacts;
    private long lina;
    private long rami;

    @BeforeEach
    void setUp() throws Exception {
        Database database = TestDatabase.freshSchema();
        RegistrationService registration = new RegistrationService(database, new PinHasher(1_000));
        lina = registration.registerPersonal("Lina", "Haddad", "03 123 456", "111111", "111111").userId();
        rami = registration.registerPersonal("Rami", "Khoury", "70 123 456", "222222", "222222").userId();
        contacts = new ContactService(database);
    }

    @Test
    void importSavesValidEntriesAndReportsTheRest() throws Exception {
        String csv = "Name,Phone\n"
                + "Rami Khoury,70 123 456\n"
                + "Nour Saad,+961 71 555 666\n"
                + "Rami again,+961 70 123 456\n"   // same number as line 2, written differently
                + ",03 999 999\n"                  // no name
                + "Bad Number,12ab\n"              // invalid
                + "Ambiguous,3123456\n"            // not a full number
                + "No Phone,\n";
        ImportReport r = contacts.importFile(lina, "friends.csv", csv);
        assertEquals(2, r.added());
        assertEquals(1, r.duplicates().size());
        assertTrue(r.duplicates().get(0).contains("appears more than once in the file"));
        assertEquals(4, r.invalid().size(), String.valueOf(r.invalid()));
        assertTrue(r.invalid().get(0).startsWith("Line 5: no name"));
        assertTrue(r.invalid().stream().anyMatch(s -> s.startsWith("Line 8 (No Phone): no phone number")));
        assertEquals(List.of("Nour Saad:96171555666", "Rami Khoury:96170123456"), names(contacts.list(lina)));

        ImportReport again = contacts.importFile(lina, "friends.csv", csv);
        assertEquals(0, again.added(), "importing the same file twice adds nothing");
        assertTrue(again.duplicates().get(0).contains("already in your contacts"));
        assertEquals(2, contacts.list(lina).size());
    }

    @Test
    void vCardImportAndEmptyOrUnreadableFiles() throws Exception {
        ImportReport r = contacts.importFile(lina, "phone.vcf",
                "BEGIN:VCARD\nFN:Sami Aoun\nTEL;TYPE=CELL:76 999 000\nEND:VCARD\n");
        assertEquals(1, r.added());
        assertThrows(ValidationException.class, () -> contacts.importFile(lina, "e.csv", "  \n"));
        assertThrows(ValidationException.class, () -> contacts.importFile(lina, "e.vcf", "hello"),
                "nothing that looks like a contact");
        ImportReport header = contacts.importFile(lina, "e.csv", "Name,Email\nX,y@z\n");
        assertEquals(0, header.added());
        assertEquals(1, header.invalid().size());
    }

    @Test
    void contactsArePrivateToTheirOwner() throws Exception {
        contacts.importFile(lina, "c.csv", "Nour Saad,71 555 666\n");
        assertTrue(contacts.list(rami).isEmpty(), "Rami can't see Lina's contacts");
        long id = contacts.list(lina).get(0).contactId();
        assertEquals(ContactService.NOT_FOUND_MESSAGE,
                assertThrows(ValidationException.class, () -> contacts.delete(rami, id)).getMessage());
        assertEquals(1, contacts.list(lina).size(), "and can't delete them");
        contacts.importFile(rami, "c.csv", "Nour Saad,71 555 666\n"); // the same number in another book is fine
        contacts.delete(lina, id);
        assertTrue(contacts.list(lina).isEmpty());
        assertEquals(1, contacts.list(rami).size());
        assertThrows(ValidationException.class, () -> contacts.delete(lina, id), "already deleted");
    }

    @Test
    void searchByNameOrNumberInAnyWrittenForm() throws Exception {
        contacts.importFile(lina, "c.csv", "Nour Saad,71 555 666\nRami Khoury,70 123 456\nSami Aoun,76 999 000\n");
        assertEquals(List.of("Rami Khoury"), searchNames("ram"));
        assertEquals(List.of("Nour Saad", "Sami Aoun"), searchNames("SA"));
        assertEquals(List.of("Rami Khoury"), searchNames("70 123"));
        assertEquals(List.of("Rami Khoury"), searchNames("070123"), "local form with a leading 0");
        assertEquals(List.of("Rami Khoury"), searchNames("+961 70"));
        assertEquals(List.of(), searchNames(" "));
        assertEquals(1, contacts.search(lina, "a", 1).size(), "limit");
    }

    private List<String> searchNames(String text) throws Exception {
        return contacts.search(lina, text, 10).stream().map(Contact::name).toList();
    }

    private static List<String> names(List<Contact> list) {
        return list.stream().map(c -> c.name() + ":" + c.phoneNumber()).toList();
    }
}
