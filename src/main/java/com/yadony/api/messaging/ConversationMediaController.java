package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.dto.ImageMessageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Photos de messagerie (FLUTTER-B4).
 *
 * <p>Contrôleur séparé de {@link ConversationController} : comme
 * {@link RecipientConversationController}, il est ouvert à tout compte authentifié (hors
 * invité, règle finale de SecurityConfig), le destinataire d'un colis n'ayant pas à porter un
 * rôle SENDER ou TRAVELER. L'autorisation réelle (participant, politique de médias) est
 * vérifiée par {@link ConversationMediaService}.
 */
@RestController
@RequestMapping("/conversations")
@PreAuthorize("isAuthenticated()")
public class ConversationMediaController {

    /** Une photo ne change jamais sous un même identifiant : cache privé d'un an, immuable. */
    static final CacheControl IMAGE_CACHE = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

    private final ConversationMediaService mediaService;
    private final UserRepository userRepository;

    public ConversationMediaController(ConversationMediaService mediaService, UserRepository userRepository) {
        this.mediaService = mediaService;
        this.userRepository = userRepository;
    }

    // POST /conversations/{id}/images — multipart : file (+ replyToId optionnel) → 201 {messageId}
    @PostMapping(path = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageMessageResponse> sendImage(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "replyToId", required = false) String replyToId) {
        UserEntity sender = resolve(firebaseUid);
        String messageId = mediaService.sendImage(id, sender, file, replyToId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ImageMessageResponse(messageId));
    }

    // GET /conversations/{id}/messages/{messageId}/image?variant=thumb|full
    @GetMapping("/{id}/messages/{messageId}/image")
    public ResponseEntity<byte[]> getImage(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id,
            @PathVariable String messageId,
            @RequestParam(value = "variant", defaultValue = "full") String variant) {
        UserEntity user = resolve(firebaseUid);
        byte[] bytes = mediaService.loadImage(id, user.getId(), messageId, parseVariant(variant));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(IMAGE_CACHE)
                .body(bytes);
    }

    private static ConversationMediaService.Variant parseVariant(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
            case "thumb" -> ConversationMediaService.Variant.THUMB;
            case "full" -> ConversationMediaService.Variant.FULL;
            default -> throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-variant",
                    "Bad Request", "variant doit valoir thumb ou full.");
        };
    }

    private UserEntity resolve(String firebaseUid) {
        if (firebaseUid == null) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "user-not-found",
                    "Unauthorized", "Utilisateur introuvable");
        }
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED, "user-not-found",
                        "Unauthorized", "Utilisateur introuvable"));
    }
}
