package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.LoginHistory;
import com.amdocs.telecom.model.enums.LoginStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Login attempts, successful or not, as required by section 2.
 */
public interface LoginHistoryDAO extends GenericDAO<LoginHistory, Long> {

    List<LoginHistory> findByUserId(Long userId, int limit);

    /**
     * Searched by username rather than id so attempts against an account
     * that does not exist are still findable.
     */
    List<LoginHistory> findByUsername(String username, int limit);

    List<LoginHistory> findByStatus(LoginStatus status, int limit);

    /**
     * How many times this username has failed since the given moment, which
     * is what the lockout rule counts.
     */
    long countFailuresSince(String username, LocalDateTime since);

    /**
     * Stamps the sign out time on an open session.
     */
    boolean recordLogout(Long loginId, LocalDateTime logoutTime);

    List<LoginHistory> findRecent(int limit);
}
