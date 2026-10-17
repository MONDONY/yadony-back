package com.yadony.api.gppro.dto;

import jakarta.validation.constraints.Size;

/** Attribution directe du badge ; la note est facultative et reste côté admin. */
public record GpProGrantRequest(@Size(max = 1000) String note) {
}
