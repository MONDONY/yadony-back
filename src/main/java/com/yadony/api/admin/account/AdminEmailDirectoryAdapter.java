package com.yadony.api.admin.account;

import com.yadony.api.kyc.AdminEmailDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Annuaire des emails d'administrateurs pour la file KYC, adosse a {@code admin_users}. */
@Component
public class AdminEmailDirectoryAdapter implements AdminEmailDirectory {

    private final AdminUserRepository adminUserRepository;

    public AdminEmailDirectoryAdapter(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> emailsOf(Collection<UUID> adminIds) {
        Map<UUID, String> emails = new HashMap<>();
        if (adminIds == null || adminIds.isEmpty()) {
            return emails;
        }
        adminUserRepository.findAllById(adminIds)
                .forEach(admin -> emails.put(admin.getId(), admin.getEmail()));
        return emails;
    }
}
