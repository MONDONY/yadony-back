package com.yadony.api.common;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessingServiceStorageTest {

    private final ImageProcessingService service = new ImageProcessingService();

    private static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void largeImage_isCappedOnLongEdge_keepingRatio() throws Exception {
        byte[] src = encode(new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB), "jpg");
        BufferedImage out = decode(service.resizeForStorage(src, 1600, 0.80));
        assertThat(out.getWidth()).isEqualTo(1600);
        assertThat(out.getHeight()).isEqualTo(1200);
    }

    @Test
    void portraitImage_cappedOnHeight() throws Exception {
        byte[] src = encode(new BufferedImage(3000, 4000, BufferedImage.TYPE_INT_RGB), "jpg");
        BufferedImage out = decode(service.resizeForStorage(src, 1600, 0.80));
        assertThat(out.getHeight()).isEqualTo(1600);
        assertThat(out.getWidth()).isEqualTo(1200);
    }

    @Test
    void smallImage_isNeverUpscaled() throws Exception {
        byte[] src = encode(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "jpg");
        BufferedImage out = decode(service.resizeForStorage(src, 1600, 0.80));
        assertThat(out.getWidth()).isEqualTo(800);
        assertThat(out.getHeight()).isEqualTo(600);
    }

    @Test
    void transparentPng_getsWhiteBackground_notBlack() throws Exception {
        BufferedImage png = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB); // entièrement transparent
        BufferedImage out = decode(service.resizeForStorage(encode(png, "png"), 1600, 0.80));
        Color c = new Color(out.getRGB(50, 50));
        assertThat(c.getRed()).isGreaterThan(240);
        assertThat(c.getGreen()).isGreaterThan(240);
        assertThat(c.getBlue()).isGreaterThan(240);
    }

    @Test
    void outputIsJpeg_withoutExif() throws Exception {
        byte[] withExif = StorageServiceMessagingImageTest.jpegWithExif();
        byte[] out = service.resizeForStorage(withExif, 1600, 0.80);
        assertThat(out[0]).isEqualTo((byte) 0xFF);
        assertThat(out[1]).isEqualTo((byte) 0xD8);
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).doesNotContain("SECRET-GPS");
    }

    @Test
    void garbage_is422() {
        assertThatThrownBy(() -> service.resizeForStorage("pas une image".getBytes(), 1600, 0.80))
                .isInstanceOf(YadonyBusinessException.class);
    }
}
