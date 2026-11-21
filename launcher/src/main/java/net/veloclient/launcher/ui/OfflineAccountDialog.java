package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.OfflineAccounts;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * "Add offline account": a username field with live validation, a preview of the offline UUID, and a note on what
 * offline accounts can and can't do. Nothing here touches the network.
 */
public final class OfflineAccountDialog {

	private OfflineAccountDialog() {
	}

	/**
	 * Shows the dialog. {@code existing} is every saved account (for the case-insensitive duplicate check); on Create
	 * {@code onCreated} gets the new offline session on the FX thread.
	 */
	public static void show(Stage owner, List<MinecraftSession> existing, Consumer<MinecraftSession> onCreated) {
		Dialog<MinecraftSession> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Add offline account");
		dialog.setHeaderText(null);

		Label heading = new Label("Add offline account");
		heading.getStyleClass().add("signin-heading");
		Label sub = new Label("Pick a username. No Microsoft sign-in needed.");
		sub.getStyleClass().add("signin-sub");

		TextField name = new TextField();
		name.setPromptText("Username (3-16 letters, numbers or _)");
		Label error = new Label();
		error.setStyle("-fx-text-fill: #FF7A7E;");
		error.setWrapText(true);
		Label warning = new Label();
		warning.setStyle("-fx-text-fill: #F7C04A;");
		warning.setWrapText(true);
		warning.setManaged(false);
		warning.setVisible(false);
		Label uuid = new Label();
		uuid.getStyleClass().add("signin-note");
		uuid.setStyle("-fx-opacity: 0.7; -fx-font-size: 11px;");

		Label note = new Label("Offline accounts work in singleplayer, LAN and offline-mode servers only. "
				+ "Friends, Store, skins and cosmetics need a Microsoft account. You play with the default Steve/Alex skin.");
		note.setWrapText(true);
		note.getStyleClass().add("signin-note");

		VBox box = new VBox(10, heading, sub, name, error, warning, uuid, note);
		box.setPadding(new Insets(8, 4, 4, 4));
		box.setPrefWidth(400);
		dialog.getDialogPane().setContent(box);

		ButtonType create = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, create);
		Button createButton = (Button) dialog.getDialogPane().lookupButton(create);
		createButton.getStyleClass().add("primary-button");
		createButton.setDisable(true);

		Runnable revalidate = () -> {
			String typed = name.getText();
			Optional<String> problem = OfflineAccounts.validateNew(typed, existing);
			boolean blank = typed == null || typed.isBlank();
			error.setText(blank ? "" : problem.orElse(""));
			createButton.setDisable(problem.isPresent());
			boolean msClash = problem.isEmpty() && OfflineAccounts.matchesMicrosoftName(typed, existing);
			warning.setText(msClash ? "One of your Microsoft accounts already uses this name." : "");
			warning.setManaged(msClash);
			warning.setVisible(msClash);
			uuid.setText(problem.isEmpty() ? "UUID " + dashed(OfflineAccounts.offlineUuid(typed.trim())) : "");
		};
		name.textProperty().addListener((obs, was, now) -> revalidate.run());
		revalidate.run();
		// Belt and braces: the button is disabled while invalid, but never accept an invalid name on Enter either.
		createButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
			if (OfflineAccounts.validateNew(name.getText(), existing).isPresent()) {
				e.consume();
			}
		});

		dialog.setResultConverter(button -> button == create ? OfflineAccounts.create(name.getText(), existing) : null);
		DialogStyling.apply(dialog);
		dialog.setOnShown(e -> Platform.runLater(name::requestFocus));
		dialog.showAndWait().ifPresent(onCreated);
	}

	private static String dashed(String dashless) {
		if (dashless.length() != 32) {
			return dashless;
		}
		return dashless.substring(0, 8) + "-" + dashless.substring(8, 12) + "-" + dashless.substring(12, 16) + "-"
				+ dashless.substring(16, 20) + "-" + dashless.substring(20);
	}
}
