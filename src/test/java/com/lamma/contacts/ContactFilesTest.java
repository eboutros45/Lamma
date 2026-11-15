package com.lamma.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lamma.contacts.ContactFiles.Entry;
import com.lamma.contacts.ContactFiles.Parsed;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContactFilesTest {

    @Test
    void csvWithoutHeaderUsesNameThenPhone() {
        Parsed p = ContactFiles.parse("contacts.csv", "Rami Khoury,70 123 456\r\n\r\nNour Saad,+961 71 555 666\n");
        assertEquals(List.of(new Entry("Rami Khoury", "70 123 456", "line 1"),
                new Entry("Nour Saad", "+961 71 555 666", "line 3")), p.entries());
        assertTrue(p.problems().isEmpty());
    }

    @Test
    void csvHeaderColumnsInAnyOrderAndQuotedFields() {
        Parsed p = ContactFiles.parse("x.csv", "Phone,Email,Name\n\"03 123 456\",lina@example.com,\"Haddad, Lina\"\n"
                + "\"70 \"\"123\"\" 456\",,Rami\n");
        assertEquals("Haddad, Lina", p.entries().get(0).name());
        assertEquals("03 123 456", p.entries().get(0).phone());
        assertEquals("70 \"123\" 456", p.entries().get(1).phone(), "doubled quotes inside a quoted field");
        assertEquals("line 3", p.entries().get(1).where());
    }

    @Test
    void googleAndOutlookStyleHeaders() {
        String google = "First Name,Middle Name,Last Name,Phone 1 - Type,Phone 1 - Value\n"
                + "Lina,,Haddad,Mobile,+961 3 123 456\n";
        Entry e = ContactFiles.parse("google.csv", google).entries().get(0);
        assertEquals(new Entry("Lina Haddad", "+961 3 123 456", "line 2"), e);
        String semicolons = "Name;Mobile Phone\nSami Aoun;76 999 000\n"; // some locales use ';'
        assertEquals(new Entry("Sami Aoun", "76 999 000", "line 2"), ContactFiles.parse("o.csv", semicolons).entries().get(0));
    }

    @Test
    void csvHeaderWithoutPhoneColumnIsReported() {
        Parsed p = ContactFiles.parse("x.csv", "Name,Email\nLina,l@example.com\n");
        assertTrue(p.entries().isEmpty());
        assertEquals("line 1", p.problems().get(0).where());
    }

    @Test
    void vCardsWithParametersFoldingAndStructuredNames() {
        String vcf = "﻿BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Lina Haddad\r\nTEL;TYPE=CELL,VOICE:+961 3 123 4\r\n 56\r\n"
                + "TEL;TYPE=WORK:01 111 111\r\nEND:VCARD\r\n"
                + "BEGIN:VCARD\nVERSION:4.0\nN:Khoury;Rami;;;\nitem1.TEL;VALUE=uri:tel:70123456\nEND:VCARD\n"
                + "BEGIN:VCARD\nFN:No Phone\nEND:VCARD\n"
                + "BEGIN:VCARD\nFN:Cut off\nTEL:70 000 000\n";
        Parsed p = ContactFiles.parse("export.vcf", vcf);
        assertEquals(new Entry("Lina Haddad", "+961 3 123 456", "card starting on line 1"), p.entries().get(0),
                "folded line joined; the first TEL is used");
        assertEquals(new Entry("Rami Khoury", "70123456", "card starting on line 7"), p.entries().get(1));
        assertEquals(new Entry("No Phone", "", "card starting on line 12"), p.entries().get(2));
        assertEquals(3, p.entries().size());
        assertEquals(1, p.problems().size(), "the incomplete last card is reported");
    }

    @Test
    void formatIsDetectedFromContentToo() {
        assertEquals("Lina", ContactFiles.parse("contacts.txt", "BEGIN:VCARD\nFN:Lina\nTEL:03123456\nEND:VCARD")
                .entries().get(0).name());
    }
}
