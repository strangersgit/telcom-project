package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.Role;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Login accounts, backing the authentication work in section 2.
 */
public interface UserDAO extends GenericDAO<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByEmail(String email);

    List<UserAccount> findByRole(Role role);

    List<UserAccount> findByStatus(AccountStatus status);

    /**
     * Records one more failed attempt and locks the account once the
     * threshold is reached.
     *
     * <p>The counter is incremented by the database rather than read into
     * Java, adjusted and written back. Two attempts arriving together would
     * otherwise each read the same count and each write the same increment,
     * losing one of them and granting a free guess.</p>
     *
     * @param lockThreshold attempts allowed before the account is locked
     * @param lockMinutes   how long the lock should last
     * @return the account as it stands after the update
     */
    Optional<UserAccount> registerFailedAttempt(Long userId, int lockThreshold, int lockMinutes);

    /**
     * Clears the failure count and stamps the login time.
     */
    boolean recordSuccessfulLogin(Long userId, LocalDateTime loginTime);

    boolean updatePassword(Long userId, String passwordHash, String passwordSalt,
                           boolean mustChangePassword);

    boolean updateStatus(Long userId, AccountStatus status, LocalDateTime lockedUntil);

    /**
     * Returns a locked account to service: active again, no expiry pending
     * and the failure count back to zero.
     *
     * <p>Clearing the count is the point. Leaving it at the threshold would
     * mean the next single mistake locked the account straight back out.</p>
     */
    boolean unlock(Long userId);

    /**
     * Accounts still holding the seeded sentinel instead of a real password.
     */
    List<UserAccount> findAwaitingPassword();
}
