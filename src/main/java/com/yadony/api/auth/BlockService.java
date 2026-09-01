package com.yadony.api.auth;

import com.yadony.api.auth.dto.BlockedUserDto;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BlockService implements BlockVisibility {

    /**
     * Transaction encore en cours : la contrepartie reste visible malgré le blocage,
     * sinon la coordination serait coupée en plein acheminement. ARRIVED (colis arrivé,
     * retrait à organiser) en fait partie — c'est le moment où les deux parties ont le
     * plus besoin de se parler.
     *
     * <p>Le blocage lui-même est toujours autorisé : il masque le reste (recherche,
     * profils, nouvelles interactions) et prend pleinement effet dès que la transaction
     * se termine.
     */
    static final List<BidStatus> ACTIVE_STATUSES = List.of(
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
            BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED,
            BidStatus.NEGOTIATING);

    private final UserBlockJpaRepository blockRepo;
    private final UserRepository userRepository;
    private final BidRepository bidRepository;

    public BlockService(UserBlockJpaRepository blockRepo, UserRepository userRepository,
                        BidRepository bidRepository) {
        this.blockRepo = blockRepo;
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
    }

    @Transactional
    public void block(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-block",
                    "Invalid Block", "Action invalide");
        }
        if (blockRepo.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            return; // idempotent
        }
        UserBlockEntity entity = new UserBlockEntity();
        entity.setBlockerId(blockerId);
        entity.setBlockedId(blockedId);
        blockRepo.save(entity);
    }

    @Transactional
    public void unblock(UUID blockerId, UUID blockedId) {
        blockRepo.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
    }

    @Transactional(readOnly = true)
    public List<BlockedUserDto> listBlocked(UUID blockerId) {
        List<UserBlockEntity> blocks = blockRepo.findByBlockerIdOrderByCreatedAtDesc(blockerId);
        Map<UUID, UserEntity> usersById = userRepository
                .findAllById(blocks.stream().map(UserBlockEntity::getBlockedId).toList())
                .stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));
        return blocks.stream()
                .map(b -> {
                    UserEntity u = usersById.get(b.getBlockedId());
                    // publicDisplayName() plutôt qu'une concaténation : getFirstName() nul
                    // produisait la chaîne littérale « null » dans la liste des comptes bloqués.
                    String name = (u != null)
                            ? u.publicDisplayName()
                            : UserEntity.UNKNOWN_DISPLAY_NAME;
                    return new BlockedUserDto(b.getBlockedId(), name, b.getCreatedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isBlockedEitherWay(UUID a, UUID b) {
        return blockRepo.existsBetween(a, b);
    }

    /**
     * Le contenu de {@code targetId} doit-il être invisible pour {@code viewerId} ?
     *
     * <p>Vrai dès qu'une relation de blocage existe dans un sens ou dans l'autre — le
     * masquage est symétrique — sauf si les deux utilisateurs ont une transaction en
     * cours, auquel cas tout reste accessible jusqu'à la fin de l'acheminement.
     *
     * <p>Un viewer anonyme (null) ne voit jamais de masquage : il n'a pas d'identité à
     * confronter aux blocages.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isHidden(UUID viewerId, UUID targetId) {
        if (viewerId == null || targetId == null || viewerId.equals(targetId)) {
            return false;
        }
        if (!blockRepo.existsBetween(viewerId, targetId)) {
            return false;
        }
        return !bidRepository.hasActiveTransactionBetween(viewerId, targetId, ACTIVE_STATUSES);
    }

    /**
     * Lève un 404 si {@code targetId} est masqué pour {@code viewerId}.
     *
     * <p>404 et non 403 : un 403 confirmerait l'existence de la ressource et rendrait le
     * blocage détectable par celui qui en fait l'objet. Le blocage doit rester silencieux.
     */
    @Override
    @Transactional(readOnly = true)
    public void assertVisible(UUID viewerId, UUID targetId) {
        if (isHidden(viewerId, targetId)) {
            throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "not-found",
                    "Not Found", "Ressource introuvable");
        }
    }

    /**
     * IDs des utilisateurs dont le contenu doit être retiré des listes présentées à
     * {@code viewerId}. Les contreparties d'une transaction en cours en sont exclues,
     * pour la même raison que dans {@link #isHidden}.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> hiddenUserIdsFor(UUID viewerId) {
        if (viewerId == null) {
            return Set.of();
        }
        List<UUID> related = blockRepo.findBlockedRelationIds(viewerId);
        if (related.isEmpty()) {
            return Set.of();
        }
        Set<UUID> stillTrading = new HashSet<>(
                bidRepository.findActiveTransactionCounterparties(viewerId, related, ACTIVE_STATUSES));
        return related.stream()
                .filter(id -> !stillTrading.contains(id))
                .collect(Collectors.toSet());
    }
}
