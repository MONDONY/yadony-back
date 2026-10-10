package com.yadony.api.common.media;

/** Nombre et volume (octets) des envois sur une période. */
public record UploadStats(long count, long bytes) {
}
