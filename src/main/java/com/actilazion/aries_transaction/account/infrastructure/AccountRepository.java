package com.actilazion.aries_transaction.account.infrastructure;

import com.actilazion.aries_transaction.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.actilazion.aries_transaction.account.domain.AccountStatus;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    // SELECT FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") UUID id);

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findAllByUserId(UUID userId);

    long countByUserIdAndStatus(UUID userId, AccountStatus status);
}
