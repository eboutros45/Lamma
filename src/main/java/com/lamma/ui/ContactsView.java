package com.lamma.ui;

import com.lamma.auth.AuthenticatedUser;
import com.lamma.auth.PhoneNumbers;
import com.lamma.contacts.ContactFiles;
import com.lamma.contacts.ContactService;
import com.lamma.contacts.ContactService.Contact;
import com.lamma.contacts.ContactService.ImportReport;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * The user's contact book: import a CSV or vCard file, search by name or number, delete.
 * Contacts are private to this user and are not LAMMA accounts.
 */
final class ContactsView extends VBox {

    private final AppNavigator navigator;
    private final AuthenticatedUser user;
    private final Label message = Screens.message("contactsMessage");
    private final VBox report = new VBox(2);
    private final TextField search = new TextField();
    private final VBox list = new VBox();
    private List<Contact> contacts = List.of();

    ContactsView(AppNavigator navigator, AuthenticatedUser user) {
        this.navigator = navigator;
        this.user = user;
        getStyleClass().add("screen");
        setStyle("-fx-max-width: 720; -fx-alignment: top-left;");

        Button importButton = Screens.withIcon(Screens.primaryButton("Import Contacts", "importContactsButton"),
                Icons.Icon.ADD);
        importButton.setDefaultButton(false);
        importButton.setOnAction(event -> chooseFile());
        report.setId("contactImportReport");

        search.setId("contactSearchField");
        search.setPromptText("Search by name or number");
        search.textProperty().addListener((obs, before, now) -> show());
        list.setId("contactList");

        VBox listCard = new VBox(8, Screens.sectionTitle("Your contacts"), search, list);
        listCard.getStyleClass().add("card");
        listCard.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(Screens.title("Contacts"),
                Screens.hint("Import contacts from a CSV file (name and phone columns) or a vCard (.vcf) file "
                        + "exported from your phone or email. Your contacts are private to you. Choosing one "
                        + "fills in a phone number; only people with a LAMMA account can receive money or "
                        + "invitations."),
                importButton, message, report, listCard);
        load(null);
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import contacts");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Contact files (*.csv, *.vcf)", "*.csv", "*.vcf", "*.vcard"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            importPath(file.toPath());
        }
    }

    /** Imports one file (called after the file is chosen; tests call it directly). */
    void importPath(Path path) {
        report.getChildren().clear();
        Screens.showInfo(message, "Importing…");
        long userId = user.userId();
        String name = path.getFileName().toString();
        BackgroundWork.run(() -> {
                    if (Files.size(path) > ContactFiles.MAX_BYTES) {
                        throw new com.lamma.auth.ValidationException(
                                "The file is too large. Contact files can be up to 1 MB.");
                    }
                    String content;
                    try {
                        content = Files.readString(path, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new com.lamma.auth.ValidationException("The file could not be read as text.");
                    }
                    return navigator.contactService().importFile(userId, name, content);
                },
                (ImportReport r) -> {
                    if (stillCurrent()) {
                        showReport(r);
                        load(null);
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        Screens.showError(message, error);
                    }
                });
    }

    private void showReport(ImportReport r) {
        String summary = r.added() + (r.added() == 1 ? " contact" : " contacts") + " imported";
        if (!r.duplicates().isEmpty()) {
            summary += ", " + r.duplicates().size() + " already saved or repeated (skipped)";
        }
        if (!r.invalid().isEmpty()) {
            summary += ", " + r.invalid().size() + " could not be imported";
        }
        summary += ".";
        if (r.added() > 0) {
            Screens.showSuccess(message, summary);
        } else {
            Screens.showError(message, summary);
        }
        report.getChildren().clear();
        for (String line : r.invalid()) {
            Label l = Screens.hint("• " + line);
            l.getStyleClass().add("import-problem");
            report.getChildren().add(l);
        }
        for (String line : r.duplicates()) {
            report.getChildren().add(Screens.hint("• Skipped: " + line));
        }
    }

    private void load(String notice) {
        list.getChildren().setAll(Screens.hint("Loading your contacts…"));
        long userId = user.userId();
        BackgroundWork.run(() -> navigator.contactService().list(userId),
                (List<Contact> loaded) -> {
                    if (stillCurrent()) {
                        contacts = loaded;
                        show();
                        if (notice != null) {
                            Screens.showSuccess(message, notice);
                        }
                    }
                },
                error -> {
                    if (stillCurrent()) {
                        list.getChildren().clear();
                        Screens.showError(message, error);
                    }
                });
    }

    private void show() {
        list.getChildren().clear();
        if (contacts.isEmpty()) {
            Label empty = Screens.hint("No contacts yet. Use Import Contacts to add some.");
            empty.setId("noContactsLabel");
            list.getChildren().add(empty);
            return;
        }
        String text = search.getText();
        int shown = 0;
        for (Contact c : contacts) {
            if (text == null || text.isBlank() || ContactService.matches(c, text)) {
                list.getChildren().add(row(c));
                shown++;
            }
        }
        if (shown == 0) {
            list.getChildren().add(Screens.hint("No contacts match “" + text.strip() + "”."));
        }
    }

    private HBox row(Contact c) {
        Label name = new Label(c.name());
        name.getStyleClass().add("activity-type");
        Label phone = Screens.caption(PhoneNumbers.format(c.phoneNumber()));
        phone.getStyleClass().add("hint");
        VBox texts = new VBox(2, name, phone);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        Button delete = new Button("Delete");
        delete.setId("deleteContact-" + c.contactId());
        HBox row = new HBox(12, Screens.avatar(first(c.name()), last(c.name()), false), texts,
                Screens.keepWidth(delete));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("activity-row");
        delete.setOnAction(event -> {
            Label question = new Label("Delete " + c.name() + "?");
            question.setWrapText(true);
            question.setMinWidth(0);
            HBox.setHgrow(question, Priority.ALWAYS);
            Button yes = new Button("Delete");
            yes.setId("confirmDeleteContact");
            yes.getStyleClass().add("danger-button");
            Button no = Screens.linkButton("Cancel", "cancelDeleteContact");
            no.setOnAction(e -> show());
            long userId = user.userId();
            yes.setOnAction(e -> {
                yes.setDisable(true);
                BackgroundWork.run(() -> {
                            navigator.contactService().delete(userId, c.contactId());
                            return null;
                        },
                        ignored -> {
                            if (stillCurrent()) {
                                load(c.name() + " was deleted.");
                            }
                        },
                        error -> {
                            if (stillCurrent()) {
                                Screens.showError(message, error);
                                load(null);
                            }
                        });
            });
            row.getChildren().setAll(question, Screens.keepWidth(yes), Screens.keepWidth(no));
        });
        return row;
    }

    private static String first(String name) {
        String[] parts = name.strip().split("\\s+");
        return parts[0];
    }

    private static String last(String name) {
        String[] parts = name.strip().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : "";
    }

    private boolean stillCurrent() {
        return navigator.session().isCurrent(user) && navigator.isShowing(this);
    }
}
