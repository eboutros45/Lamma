package com.lamma.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads contact files: CSV (with or without a header row) and vCard (.vcf). Pure parsing, no
 * database; phone numbers are returned as written and validated later by {@link ContactService}.
 *
 * <p>CSV: with a header, the name comes from a "Name" / "Full Name" / "Display Name" column, or
 * from "First Name" + "Last Name" (as exported by Google and Outlook), and the phone from the
 * first column whose header mentions "phone", "mobile" or "tel". Without a header, column 1 is
 * the name and column 2 the phone. Quoted fields ("Haddad, Lina") are supported.
 *
 * <p>vCard: one contact per BEGIN:VCARD ... END:VCARD block; the name from FN (or N), the phone
 * from the first TEL line. Folded lines are joined.
 */
public final class ContactFiles {

    /** Largest file accepted (contact lists are small; this stops accidental huge files). */
    public static final int MAX_BYTES = 1_000_000;
    /** Most contacts read from one file. */
    public static final int MAX_ENTRIES = 2_000;

    /** One contact as found in the file, before validation. {@code where} is e.g. "line 4". */
    public record Entry(String name, String phone, String where) {
    }

    /** A part of the file that could not be read as a contact. */
    public record Problem(String where, String message) {
    }

    public record Parsed(List<Entry> entries, List<Problem> problems) {
    }

    private ContactFiles() {
    }

    /** Chooses the format from the file name (.vcf / .vcard → vCard, anything else → CSV). */
    public static Parsed parse(String fileName, String content) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text = content == null ? "" : stripBom(content);
        if (lower.endsWith(".vcf") || lower.endsWith(".vcard") || text.stripLeading().toUpperCase(Locale.ROOT)
                .startsWith("BEGIN:VCARD")) {
            return parseVCard(text);
        }
        return parseCsv(text);
    }

    // ---------- CSV ----------

    public static Parsed parseCsv(String content) {
        List<Entry> entries = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        List<List<String>> rows = csvRows(content);
        if (rows.isEmpty()) {
            return new Parsed(entries, problems);
        }
        int nameCol = 0;
        int firstCol = -1;
        int lastCol = -1;
        int phoneCol = 1;
        int start = 0;
        List<String> header = rows.get(0);
        if (looksLikeHeader(header)) {
            start = 1;
            nameCol = -1;
            phoneCol = -1;
            for (int i = 0; i < header.size(); i++) {
                String h = header.get(i).strip().toLowerCase(Locale.ROOT);
                if (nameCol < 0 && (h.equals("name") || h.equals("full name") || h.equals("display name"))) {
                    nameCol = i;
                } else if (firstCol < 0 && (h.equals("first name") || h.equals("given name"))) {
                    firstCol = i;
                } else if (lastCol < 0 && (h.equals("last name") || h.equals("family name") || h.equals("surname"))) {
                    lastCol = i;
                } else if (phoneCol < 0 && (h.contains("phone") || h.contains("mobile") || h.contains("tel"))
                        && !h.contains("type") && !h.contains("label")) {
                    phoneCol = i;
                }
            }
            if (phoneCol < 0 || (nameCol < 0 && firstCol < 0 && lastCol < 0)) {
                problems.add(new Problem("line 1", "The header needs a name column and a phone column."));
                return new Parsed(entries, problems);
            }
        }
        for (int r = start; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String where = "line " + (r + 1);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            if (entries.size() >= MAX_ENTRIES) {
                problems.add(new Problem(where, "Only the first " + MAX_ENTRIES + " contacts are read."));
                break;
            }
            String name = nameCol >= 0 ? cell(row, nameCol)
                    : (cell(row, firstCol) + " " + cell(row, lastCol)).strip();
            String phone = cell(row, phoneCol);
            entries.add(new Entry(name.strip(), phone.strip(), where));
        }
        return new Parsed(entries, problems);
    }

    private static boolean looksLikeHeader(List<String> row) {
        for (String cell : row) {
            String h = cell.strip().toLowerCase(Locale.ROOT);
            if (h.equals("name") || h.contains("phone") || h.equals("first name") || h.equals("full name")
                    || h.contains("mobile") || h.equals("display name")) {
                return true;
            }
        }
        return false;
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    /** RFC 4180-style rows: commas or semicolons, quoted fields, doubled quotes, newlines inside quotes. */
    static List<List<String>> csvRows(String content) {
        char separator = guessSeparator(content);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Semicolon-separated files (common in some locales) when the first line has no commas. */
    private static char guessSeparator(String content) {
        int end = content.indexOf('\n');
        String first = end < 0 ? content : content.substring(0, end);
        return first.indexOf(',') < 0 && first.indexOf(';') >= 0 ? ';' : ',';
    }

    // ---------- vCard ----------

    public static Parsed parseVCard(String content) {
        List<Entry> entries = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        List<String> lines = unfold(content);
        String fn = null;
        String n = null;
        String tel = null;
        int startLine = 0;
        boolean inCard = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("BEGIN:VCARD")) {
                inCard = true;
                fn = null;
                n = null;
                tel = null;
                startLine = i + 1;
                continue;
            }
            if (!inCard) {
                continue;
            }
            if (upper.startsWith("END:VCARD")) {
                inCard = false;
                String where = "card starting on line " + startLine;
                if (entries.size() >= MAX_ENTRIES) {
                    problems.add(new Problem(where, "Only the first " + MAX_ENTRIES + " contacts are read."));
                    break;
                }
                String name = fn != null && !fn.isBlank() ? fn : nameFromN(n);
                entries.add(new Entry(name == null ? "" : name.strip(), tel == null ? "" : tel.strip(), where));
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String property = upper.substring(0, colon);
            String value = unescape(line.substring(colon + 1));
            String key = property.contains(";") ? property.substring(0, property.indexOf(';')) : property;
            if (key.contains(".")) { // grouped properties such as "item1.TEL"
                key = key.substring(key.lastIndexOf('.') + 1);
            }
            switch (key) {
                case "FN" -> fn = value;
                case "N" -> n = value;
                case "TEL" -> {
                    if (tel == null) {
                        tel = value.toLowerCase(Locale.ROOT).startsWith("tel:") ? value.substring(4) : value;
                    }
                }
                default -> { }
            }
        }
        if (inCard) {
            problems.add(new Problem("card starting on line " + startLine, "The card is not complete (no END:VCARD)."));
        }
        return new Parsed(entries, problems);
    }

    /** "Haddad;Lina;;;" → "Lina Haddad". */
    private static String nameFromN(String n) {
        if (n == null) {
            return null;
        }
        String[] parts = n.split(";", -1);
        String family = parts.length > 0 ? parts[0].strip() : "";
        String given = parts.length > 1 ? parts[1].strip() : "";
        return (given + " " + family).strip();
    }

    private static List<String> unfold(String content) {
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\r\n|\n|\r")) {
            if (!lines.isEmpty() && (raw.startsWith(" ") || raw.startsWith("\t"))) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + raw.substring(1));
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }

    private static String unescape(String value) {
        return value.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").replace("\\N", " ")
                .replace("\\\\", "\\");
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
