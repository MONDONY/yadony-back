package com.yadony.api.videomodal;

/**
 * Seuils des moments qui en ont un : {@code fewResultsMax} (recherche avec peu de résultats, 1 à 20,
 * défaut 2) et {@code tripViewedTimes} (même trajet consulté N fois, 2 à 10, défaut 3). Sans effet
 * si le déclencheur concerné n'est pas coché.
 */
public record VideoModalTriggerOptions(int fewResultsMax, int tripViewedTimes) {

    public static final int DEFAULT_FEW_RESULTS_MAX = 2;
    public static final int DEFAULT_TRIP_VIEWED_TIMES = 3;
    public static final int MIN_FEW_RESULTS_MAX = 1;
    public static final int MAX_FEW_RESULTS_MAX = 20;
    public static final int MIN_TRIP_VIEWED_TIMES = 2;
    public static final int MAX_TRIP_VIEWED_TIMES = 10;

    public static final VideoModalTriggerOptions DEFAULTS =
            new VideoModalTriggerOptions(DEFAULT_FEW_RESULTS_MAX, DEFAULT_TRIP_VIEWED_TIMES);
}
