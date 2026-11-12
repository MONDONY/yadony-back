package com.yadony.api.automation;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.automation.dto.AutomationHistoryResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/travelers/me/automation-history")
public class AutomationHistoryController {

    private final AutomationRuleService ruleService;
    private final UserRepository userRepository;

    public AutomationHistoryController(AutomationRuleService ruleService, UserRepository userRepository) {
        this.ruleService = ruleService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<AutomationHistoryResponse>> listHistory() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthenticated", "problem.unauthenticated");
        }
        UserEntity user = userRepository.findByFirebaseUid(auth.getName())
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "pro-required", "problem.automation.pro-required");
        }
        return ResponseEntity.ok(ruleService.listHistory(user.getId()));
    }

    @GetMapping("/today-count")
    public ResponseEntity<Map<String, Long>> getTodayCount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw YadonyBusinessException.localized(HttpStatus.UNAUTHORIZED, "unauthenticated", "problem.unauthenticated");
        }
        UserEntity user = userRepository.findByFirebaseUid(auth.getName())
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "pro-required", "problem.automation.pro-required");
        }
        return ResponseEntity.ok(Map.of("count", ruleService.countTodayActions(user.getId())));
    }
}
