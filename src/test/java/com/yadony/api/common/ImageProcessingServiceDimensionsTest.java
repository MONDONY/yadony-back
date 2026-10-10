package com.yadony.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Garde contre les bombes de décompression : dimensions lues avant tout décodage. */
class ImageProcessingServiceDimensionsTest {

    private final ImageProcessingService service = new ImageProcessingService();

    /** PNG minimal : signature + IHDR déclarant {@code w × h} + IEND, sans aucun pixel. */
    static byte[] pngHeaderOnly(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream ih = new DataOutputStream(ihdr);
        ih.writeInt(w);
        ih.writeInt(h);
        ih.writeByte(8);  // profondeur
        ih.writeByte(2);  // RGB
        ih.writeByte(0);
        ih.writeByte(0);
        ih.writeByte(0);
        chunk(data, "IHDR", ihdr.toByteArray());
        chunk(data, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(DataOutputStream data, String type, byte[] body) throws Exception {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        data.writeInt(body.length);
        data.write(t);
        data.write(body);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(body);
        data.writeInt((int) crc.getValue());
    }

    @Test
    void headerDeclaring20000x20000_isRejectedBeforeDecoding_onStorage() throws Exception {
        byte[] bomb = pngHeaderOnly(20_000, 20_000);
        assertThatThrownBy(() -> service.resizeForStorage(bomb, 1600, 0.80))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("image/too-large");
                    assertThat(e.getMessage()).isEqualTo("Image trop grande (dimensions)");
                });
    }

    @Test
    void headerDeclaring20000x20000_isRejected_onChatProcess() throws Exception {
        byte[] bomb = pngHeaderOnly(20_000, 20_000);
        assertThatThrownBy(() -> service.process(bomb, "image/png"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("image/too-large"));
    }

    @Test
    void justAboveLimit_8000x6000_isRejected() throws Exception {
        assertThatThrownBy(() -> service.resizeForStorage(pngHeaderOnly(8000, 6000), 1600, 0.80))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("image/too-large"));
    }

    @Test
    void image6000x4000_passes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6000, 4000, BufferedImage.TYPE_BYTE_GRAY), "png", out);
        byte[] result = service.resizeForStorage(out.toByteArray(), 1600, 0.80);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(decoded.getWidth()).isEqualTo(1600);
        assertThat(decoded.getHeight()).isEqualTo(1067);
    }
}
