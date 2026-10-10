package com.yadony.api.common;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Processes uploaded images for request photos:
 * - Resizes the main image so that the long edge ≤ 1280 px (keeping aspect ratio)
 * - Produces a 400×400 px center-cropped thumbnail
 * - Outputs JPEG at 0.80 quality to keep files compact
 * - Strips EXIF metadata as a side-effect of re-encoding via Thumbnailator
 */
@Service
public class ImageProcessingService {

    private static final int MAX_LONG_EDGE = 1280;
    private static final int THUMB = 400;
    private static final double QUALITY = 0.80;

    public static final int STORAGE_LONG_EDGE = 1600;
    public static final double STORAGE_QUALITY = 0.80;
    public static final int AVATAR_LONG_EDGE = 512;
    public static final double AVATAR_QUALITY = 0.82;

    /** Container for the two derived images produced by {@link #process}. */
    public record ProcessedImage(byte[] main, byte[] thumbnail) {}

    /**
     * Validates, resizes and thumbnails the supplied raw image bytes.
     *
     * @param input       raw bytes of the original upload
     * @param contentType MIME type declared by the client (used only for error context)
     * @return {@link ProcessedImage} with the resized main image and a square thumbnail
     * @throws YadonyBusinessException (422) if the bytes cannot be decoded as an image
     *                               or if resizing fails
     */
    public ProcessedImage process(byte[] input, String contentType) {
        BufferedImage src = decodeImage(input);

        try {
            ByteArrayOutputStream mainOut = new ByteArrayOutputStream();
            Thumbnails.of(src)
                    .size(MAX_LONG_EDGE, MAX_LONG_EDGE)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(QUALITY)
                    .toOutputStream(mainOut);

            ByteArrayOutputStream thumbOut = new ByteArrayOutputStream();
            Thumbnails.of(src)
                    .size(THUMB, THUMB)
                    .crop(Positions.CENTER)
                    .outputFormat("jpg")
                    .outputQuality(QUALITY)
                    .toOutputStream(thumbOut);

            return new ProcessedImage(mainOut.toByteArray(), thumbOut.toByteArray());
        } catch (Exception e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "image/processing-failed",
                    "Image Processing Failed",
                    "Échec du traitement de l'image : " + e.getMessage());
        }
    }

    /**
     * Image de stockage : grand côté plafonné à {@code maxLongEdge} (jamais agrandie),
     * JPEG à {@code quality}, EXIF retirés par le ré-encodage, transparence posée sur blanc
     * (un PNG transparent passé tel quel en JPEG sortirait noir).
     */
    public byte[] resizeForStorage(byte[] input, int maxLongEdge, double quality) {
        BufferedImage src = flattenOnWhite(decodeImage(input));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var builder = Thumbnails.of(src);
            if (Math.max(src.getWidth(), src.getHeight()) > maxLongEdge) {
                builder.size(maxLongEdge, maxLongEdge).keepAspectRatio(true);
            } else {
                builder.scale(1.0);
            }
            builder.outputFormat("jpg").outputQuality(quality).toOutputStream(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "image/processing-failed", "Image Processing Failed",
                    "Échec du traitement de l'image : " + e.getMessage());
        }
    }

    private static BufferedImage flattenOnWhite(BufferedImage src) {
        if (!src.getColorModel().hasAlpha()) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private BufferedImage decodeImage(byte[] input) {
        if (input == null || input.length == 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "image/invalid",
                    "Invalid Image",
                    "Le fichier est vide ou absent");
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(input));
            if (img == null) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "image/invalid",
                        "Invalid Image",
                        "Le fichier n'est pas une image valide");
            }
            return img;
        } catch (YadonyBusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "image/invalid",
                    "Invalid Image",
                    "Le fichier n'est pas une image valide");
        }
    }
}
