package com.yadony.api.auth;

import com.yadony.api.auth.dto.BlockedUserDto;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BlockService {

    /** Transaction encore en cours : bloquer la contrepartie couperait la
     *  coordination. ARRIVED (colis arrivé, retrait à organiser) en fait partie. */
    private static final List<BidStatus> ACTIVE_STATUSES = List.of(
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED,
            BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

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
        if (bidRepository.hasActiveTransactionBetween(blockerId, blockedId, ACTIVE_STATUSES)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "active-transaction",
                    "Active Transaction",
                    "Termine d'abord la transaction en cours avant de bloquer cet utilisateur");
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
}
