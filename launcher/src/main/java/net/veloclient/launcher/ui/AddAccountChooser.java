package net.veloclient.launcher.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

/**
 * The "no account yet" prompt (Play, server join, any action that needs an account): sign in with Microsoft, or
 * play with an offline account. Cancelling does nothing.
 */
public final class AddAccountChooser {

	private AddAccountChooser() {
	}

	public static void show(Stage owner, Runnable microsoft, Runnable offline) {
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.initOwner(owner);
		alert.setTitle("Choose an account");
		alert.setHeaderText("How do you want to play?");
		alert.setContentText("Sign in with the Microsoft account that owns Minecraft for online servers, Friends and the Store - "
				+ "or play offline (singleplayer, LAN and offline-mode servers) with just a username.");
		ButtonType ms = new ButtonType("Sign in with Microsoft", ButtonBar.ButtonData.OK_DONE);
		ButtonType off = new ButtonType("Play offline", ButtonBar.ButtonData.OTHER);
		alert.getButtonTypes().setAll(ButtonType.CANCEL, off, ms);
		DialogStyling.apply(alert);
		alert.showAndWait().ifPresent(choice -> {
			if (choice == ms) {
				microsoft.run();
			} else if (choice == off) {
				offline.run();
			}
		});
	}
}
