package com.yadony.api.subscriptions;

import com.yadony.api.subscriptions.dto.SubscriberResponse;
import com.yadony.api.subscriptions.dto.SubscriptionItemResponse;
import com.yadony.api.subscriptions.dto.SubscriptionStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/travelers/{travelerId}/subscribe")
    @PreAuthorize("hasRole('SENDER')")
    @ResponseStatus(HttpStatus.CREATED)
    public void subscribe(@AuthenticationPrincipal String firebaseUid,
                          @PathVariable UUID travelerId) {
        subscriptionService.subscribe(firebaseUid, travelerId);
    }

    @DeleteMapping("/travelers/{travelerId}/subscribe")
    @PreAuthorize("hasRole('SENDER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal String firebaseUid,
                            @PathVariable UUID travelerId) {
        subscriptionService.unsubscribe(firebaseUid, travelerId);
    }

    @PutMapping("/travelers/{travelerId}/subscribe/push")
    @PreAuthorize("hasRole('SENDER')")
    public SubscriptionStatusResponse setPush(@AuthenticationPrincipal String firebaseUid,
                                              @PathVariable UUID travelerId,
                                              @RequestBody Map<String, Boolean> body) {
        subscriptionService.setPush(firebaseUid, travelerId, Boolean.TRUE.equals(body.get("enabled")));
        return subscriptionService.getStatus(firebaseUid, travelerId);
    }

    @GetMapping("/travelers/{travelerId}/subscription")
    @PreAuthorize("hasRole('SENDER')")
    public SubscriptionStatusResponse status(@AuthenticationPrincipal String firebaseUid,
                                             @PathVariable UUID travelerId) {
        return subscriptionService.getStatus(firebaseUid, travelerId);
    }

    @GetMapping("/me/subscriptions")
    @PreAuthorize("hasRole('SENDER')")
    public List<SubscriptionItemResponse> mySubscriptions(@AuthenticationPrincipal String firebaseUid) {
        return subscriptionService.getMySubscriptions(firebaseUid);
    }

    @GetMapping("/me/subscribers")
    @PreAuthorize("hasRole('TRAVELER')")
    public List<SubscriberResponse> mySubscribers(@AuthenticationPrincipal String firebaseUid) {
        return subscriptionService.getMySubscribers(firebaseUid);
    }

    @PostMapping("/me/subscriptions/mark-seen")
    @PreAuthorize("hasRole('SENDER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllSeen(@AuthenticationPrincipal String firebaseUid) {
        subscriptionService.markAllSeen(firebaseUid);
    }

    @PostMapping("/me/subscriptions/{travelerId}/mark-seen")
    @PreAuthorize("hasRole('SENDER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markSeen(@AuthenticationPrincipal String firebaseUid,
                         @PathVariable UUID travelerId) {
        subscriptionService.markSeen(firebaseUid, travelerId);
    }
}
