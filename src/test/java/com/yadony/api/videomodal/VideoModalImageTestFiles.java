package com.yadony.api.videomodal;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** Images de test : JPEG et PNG générés, WebP sans perte 1 x 1 (lisible par le lecteur du classpath). */
final class VideoModalImageTestFiles {

    private VideoModalImageTestFiles() {
    }

    static byte[] image(String format, int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    static byte[] webp() {
        return Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");
    }

    /** Octets de tête JPEG valides suivis de remplissage : passe le contrôle de signature, pas le décodage. */
    static byte[] jpegHeaderOnly(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }
}
