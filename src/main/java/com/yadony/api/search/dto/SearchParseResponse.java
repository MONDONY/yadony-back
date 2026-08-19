package com.yadony.api.search.dto;

import java.util.List;

/**
 * @param filters    les filtres à appliquer, champs nuls omis à la sérialisation
 * @param recognized ce qui a été compris, pour le récapitulatif et le surlignage
 * @param unresolved ce qui reste à trancher, transformé en question par le client
 * @param ignored    les mots qu'aucune passe n'a réclamés, pour le diagnostic
 */
public record SearchParseResponse(
        ParsedFilters filters,
        List<RecognizedField> recognized,
        List<UnresolvedItem> unresolved,
        List<String> ignored
) {}
