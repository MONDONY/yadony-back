package com.yadony.api.common;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StorageControllerIT {

    @Autowired private MockMvc mockMvc;
    @MockBean private StorageService storageService;
    @MockBean private UserRepository userRepository;
    @MockBean private BidRepository bidRepository;
    @MockBean private AnnouncementRepository announcementRepository;

    private static UsernamePasswordAuthenticationToken authAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
            uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static void setId(UserEntity user, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MockMultipartFile jpeg() {
        // En-tête JPEG (FF D8 FF) — StorageService est mocké, le contenu importe peu.
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg",
            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
    }

    @Test
    void uploadPackageRequest_uidWithSpecialChars_resolvesUserAndReturnsKey() throws Exception {
        // Régression : un uid contenant un caractère hors [A-Za-z0-9_-] (ex. dev/phone)
        // ne doit PLUS être rejeté par invalid-uid — le prefix utilise user.getId().
        final String uid = "dev+221:7712";
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        setId(user, userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(user));
        when(storageService.storeImage(any(), argThat(u -> u != null
                && u.prefix().startsWith("package_requests/" + userId + "/")
                && u.purpose() == MediaPurpose.PACKAGE_REQUEST && userId.equals(u.ownerId()))))
            .thenReturn("package_requests/" + userId + "/1.jpg");
        when(storageService.generatePresignedUrl(eq("package_requests/" + userId + "/1.jpg"),
            any(Duration.class))).thenReturn("https://signed/1");

        mockMvc.perform(multipart("/storage/upload/package-request")
                .file(jpeg())
                .with(authentication(authAs(uid))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("package_requests/" + userId + "/1.jpg"))
            .andExpect(jsonPath("$.url").value("https://signed/1"));
    }

    @Test
    void uploadPackageRequest_unknownUser_returns401() throws Exception {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(multipart("/storage/upload/package-request")
                .file(jpeg())
                .with(authentication(authAs("ghost"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadProfile_endpointRemoved() throws Exception {
        mockMvc.perform(multipart("/storage/upload/profile").file(jpeg())
                .with(authentication(authAs("uid-1"))))
            .andExpect(status().is4xxClientError())
            .andExpect(result -> org.assertj.core.api.Assertions
                .assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
    }

    private UUID stubTrackingFixture(String uid) {
        UUID travelerId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UserEntity traveler = new UserEntity();
        setId(traveler, travelerId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(traveler));
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        trackingTravelerId = travelerId;
        return bidId;
    }

    private UUID trackingTravelerId;

    @Test
    void uploadTracking_usesTrackingMediaUploadWithTravelerAsOwner() throws Exception {
        UUID bidId = stubTrackingFixture("trav");
        when(storageService.storeImage(any(), argThat(u -> u != null
                && u.prefix().equals("tracking/" + bidId + "/")
                && u.purpose() == MediaPurpose.TRACKING
                && bidId.equals(u.bidId()) && trackingTravelerId.equals(u.ownerId()))))
            .thenReturn("tracking/" + bidId + "/1.jpg");
        when(storageService.generatePresignedUrl(eq("tracking/" + bidId + "/1.jpg"), any(Duration.class)))
            .thenReturn("https://signed/t");

        mockMvc.perform(multipart("/storage/upload/tracking").file(jpeg()).param("bidId", bidId.toString())
                .with(authentication(authAs("trav"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("tracking/" + bidId + "/1.jpg"));
    }

    @Test
    void uploadTracking_quotaExceeded_is429ProblemJson() throws Exception {
        UUID bidId = stubTrackingFixture("trav");
        when(storageService.storeImage(any(), any())).thenThrow(new YadonyBusinessException(
            HttpStatus.TOO_MANY_REQUESTS, "tracking-photo-limit-reached", "Too Many Requests", "max"));

        mockMvc.perform(multipart("/storage/upload/tracking").file(jpeg()).param("bidId", bidId.toString())
                .with(authentication(authAs("trav"))))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.type")
                .value(org.hamcrest.Matchers.containsString("tracking-photo-limit-reached")));
    }
}
