package com.yadony.api.common.media;

/**
 * Bilan d'un participant ou d'un passage. {@code eligible} = ce qui serait/est supprimé ;
 * en mode à blanc {@code deleted} vaut toujours 0.
 */
public record PhotoPurgeOutcome(int eligible, int deleted, int failed) {
    public static final PhotoPurgeOutcome NONE = new PhotoPurgeOutcome(0, 0, 0);

    public PhotoPurgeOutcome plus(PhotoPurgeOutcome o) {
        return new PhotoPurgeOutcome(eligible + o.eligible, deleted + o.deleted, failed + o.failed);
    }
}
