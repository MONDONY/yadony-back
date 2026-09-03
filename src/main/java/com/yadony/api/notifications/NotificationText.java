package com.yadony.api.notifications;

/** Un titre et un corps de notification, prêts à persister et à pousser. */
public record NotificationText(String title, String body) {
}
