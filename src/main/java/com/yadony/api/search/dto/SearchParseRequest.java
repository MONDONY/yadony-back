package com.yadony.api.search.dto;

import com.yadony.api.search.SearchMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * @param text  la phrase saisie ou dictée
 * @param mode  change l'interprétation du poids (capacité exigée ou capacité offerte)
 * @param today facultatif, sert aux tests déterministes ; la date du serveur sinon
 */
public record SearchParseRequest(
        @NotBlank @Size(max = 200) String text,
        @NotNull SearchMode mode,
        LocalDate today
) {}
