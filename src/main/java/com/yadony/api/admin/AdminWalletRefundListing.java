package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminWalletRefundRequestResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Habille une page de demandes de remboursement wallet pour l'admin : nom de l'utilisateur
 * (comptes supprimés compris, d'où la lecture native) et origine de la demande.
 */
@Component
public class AdminWalletRefundListing {

    /**
     * Le règlement du wallet précède de quelques secondes la pose de la date de suppression
     * (suppression immédiate) : marge pour rattacher la demande à la suppression.
     */
    static final long DELETION_MARGIN_MINUTES = 5;

    private final UserRepository userRepository;

    public AdminWalletRefundListing(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Page<AdminWalletRefundRequestResponse> describe(Page<WalletRefundRequestEntity> page) {
        var userIds = page.getContent().stream().map(WalletRefundRequestEntity::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, UserEntity> users = userIds.isEmpty() ? Map.of()
                : userRepository.findAllByIdIncludingDeleted(userIds).stream()
                        .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return page.map(r -> {
            UserEntity user = users.get(r.getUserId());
            return AdminWalletRefundRequestResponse.from(r, displayName(user), origin(r, user));
        });
    }

    static String displayName(UserEntity user) {
        if (user == null) {
            return null;
        }
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? null : name;
    }

    static String origin(WalletRefundRequestEntity request, UserEntity user) {
        LocalDateTime deletion = deletionMoment(user);
        if (deletion != null && request.getRequestedAt() != null
                && !request.getRequestedAt().isBefore(deletion.minusMinutes(DELETION_MARGIN_MINUTES))) {
            return AdminWalletRefundRequestResponse.ORIGIN_ACCOUNT_DELETION;
        }
        return AdminWalletRefundRequestResponse.ORIGIN_WALLET;
    }

    private static LocalDateTime deletionMoment(UserEntity user) {
        if (user == null) {
            return null;
        }
        if (user.getDeletionRequestedAt() != null) {
            return LocalDateTime.ofInstant(user.getDeletionRequestedAt(), ZoneOffset.UTC);
        }
        return user.getDeletedAt();
    }
}
