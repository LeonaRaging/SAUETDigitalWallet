package sa.uet.digitalwallet_system.adapter.out.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sa.uet.digitalwallet_system.adapter.out.persistence.entity.WalletJpaEntity;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<WalletJpaEntity, Long> {
    Optional<WalletJpaEntity> findByUserId(Long userId);
    Optional<WalletJpaEntity> findByWalletNumber(String walletNumber);

    // Conditional UPDATE: MySQL checks and debits in one statement under a row lock,
    // so concurrent debits run one after another and the loser sees 0 rows updated.
    // Bulk UPDATE bypasses @Version, so the version is bumped manually.
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE WalletJpaEntity w
            SET w.balance = w.balance - :amount, w.version = w.version + 1, w.updatedAt = CURRENT_TIMESTAMP
            WHERE w.id = :id AND w.status = 'ACTIVE' AND w.balance >= :amount
            """)
    int debitIfSufficient(@Param("id") Long id, @Param("amount") long amount);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE WalletJpaEntity w
            SET w.balance = w.balance + :amount, w.version = w.version + 1, w.updatedAt = CURRENT_TIMESTAMP
            WHERE w.id = :id AND w.status = 'ACTIVE'
            """)
    int credit(@Param("id") Long id, @Param("amount") long amount);
}
