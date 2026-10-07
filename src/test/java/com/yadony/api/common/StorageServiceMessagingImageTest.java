package com.yadony.api.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageService — photos de messagerie (FLUTTER-B4)")
class StorageServiceMessagingImageTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;

    private StorageService storageService;

    @BeforeEach
    void setUp() throws Exception {
        storageService = new StorageService(s3Client, s3Presigner, new ImageProcessingService());
        var field = StorageService.class.getDeclaredField("bucket");
        field.setAccessible(true);
        field.set(storageService, "test-bucket");
    }

    /** JPEG valide portant un segment APP1 Exif avec une donnée sensible (fausse position GPS). */
    static byte[] jpegWithExif() throws Exception {
        BufferedImage img = new BufferedImage(2000, 1500, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        byte[] jpeg = out.toByteArray();

        byte[] payload = "Exif\0\0SECRET-GPS-14.6928N-17.4467W".getBytes(StandardCharsets.ISO_8859_1);
        int len = payload.length + 2;
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(jpeg, 0, 2); // SOI
        withExif.write(0xFF);
        withExif.write(0xE1); // APP1
        withExif.write((len >> 8) & 0xFF);
        withExif.write(len & 0xFF);
        withExif.write(payload);
        withExif.write(jpeg, 2, jpeg.length - 2);
        return withExif.toByteArray();
    }

    private static byte[] readAll(RequestBody body) throws Exception {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("ré-encode la photo : EXIF supprimées, clés _full/_thumb sous le préfixe, JPEG")
    void storeMessagingImage_stripsExif_andWritesBothKeys() throws Exception {
        byte[] original = jpegWithExif();
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("SECRET-GPS");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        StorageService.StoredImage stored = storageService.storeMessagingImage(
                "messaging/conv_1/", "AbC123", original, "image/jpeg");

        assertThat(stored.mainKey()).isEqualTo("messaging/conv_1/AbC123_full.jpg");
        assertThat(stored.thumbnailKey()).isEqualTo("messaging/conv_1/AbC123_thumb.jpg");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(2)).putObject(req.capture(), body.capture());
        assertThat(req.getAllValues()).extracting(PutObjectRequest::key)
                .containsExactly(stored.mainKey(), stored.thumbnailKey());
        assertThat(req.getAllValues()).extracting(PutObjectRequest::contentType)
                .containsOnly("image/jpeg");
        for (RequestBody b : body.getAllValues()) {
            String written = new String(readAll(b), StandardCharsets.ISO_8859_1);
            assertThat(written).doesNotContain("SECRET-GPS").doesNotContain("Exif");
        }
    }

    @Test
    @DisplayName("préfixe hors liste blanche → 400")
    void storeMessagingImage_rejectsUnknownPrefix() {
        assertThatThrownBy(() -> storageService.storeMessagingImage("evil/", "x", new byte[]{1}, "image/jpeg"))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("échec de la miniature → la grande image déjà écrite est supprimée")
    void storeMessagingImage_cleansMainKey_whenThumbFails() throws Exception {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> storageService.storeMessagingImage(
                "messaging/conv_1/", "AbC123", jpegWithExif(), "image/jpeg"))
                .isInstanceOf(S3Exception.class);

        ArgumentCaptor<DeleteObjectRequest> del = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(del.capture());
        assertThat(del.getValue().key()).isEqualTo("messaging/conv_1/AbC123_full.jpg");
    }

    @Test
    @DisplayName("validateImageUpload : en-tête incohérent avec le type déclaré → 422")
    void validateImageUpload_rejectsSpoofedContent() {
        MockMultipartFile pdf = new MockMultipartFile("file", "x.jpg", "image/jpeg",
                "%PDF-1.7 not an image".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> storageService.validateImageUpload(pdf))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    @DisplayName("validateImageUpload : JPEG valide accepté")
    void validateImageUpload_acceptsJpeg() throws Exception {
        MockMultipartFile jpeg = new MockMultipartFile("file", "x.jpg", "image/jpeg", jpegWithExif());
        assertThatCode(() -> storageService.validateImageUpload(jpeg)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("downloadBytes : octets de l'objet, vide si la clé n'existe plus")
    void downloadBytes_returnsBytes_orEmptyWhenMissing() {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[]{1, 2, 3}))
                .thenThrow(NoSuchKeyException.builder().message("gone").build());

        assertThat(storageService.downloadBytes("messaging/a")).contains(new byte[]{1, 2, 3});
        assertThat(storageService.downloadBytes("messaging/b")).isEmpty();
    }

    @Test
    @DisplayName("downloadBytes : une panne R2 remonte (pas confondue avec une photo expirée)")
    void downloadBytes_propagatesOtherErrors() {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("down").build());
        assertThatThrownBy(() -> storageService.downloadBytes("messaging/a")).isInstanceOf(S3Exception.class);
    }

    @Test
    @DisplayName("deleteQuietly n'échoue jamais")
    void deleteQuietly_swallowsErrors() {
        doThrow(S3Exception.builder().message("down").build()).when(s3Client).deleteObject(any(DeleteObjectRequest.class));
        assertThatCode(() -> storageService.deleteQuietly("messaging/a")).doesNotThrowAnyException();
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }
}
