package com.yadony.api.videomodal;

/** Point fort d'une campagne, stocké dans la colonne JSON {@code highlights}. */
public record VideoModalHighlight(VideoModalIcon icon, String textFr, String textEn) {
}
