package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;

import java.util.List;
import java.util.Optional;

/**
 * The engineer roster and its live workload, feeding the assignment engine
 * in sections 7 and 9.
 */
public interface NetworkEngineerDAO extends GenericDAO<NetworkEngineer, Long> {

    Optional<NetworkEngineer> findByEmployeeCode(String employeeCode);

    Optional<NetworkEngineer> findByUserId(Long userId);

    List<NetworkEngineer> findBySpecialization(Specialization specialization);

    List<NetworkEngineer> findByRegion(Region region);

    /**
     * Engineers who could take this ticket now: right skill, right region,
     * on duty and below their capacity. A null region widens the search
     * beyond the ticket's own area.
     */
    List<NetworkEngineer> findAvailableFor(Specialization specialization, Region region);

    boolean updateAvailability(Long engineerId, EngineerAvailability availability);

    /**
     * Adds one to the workload, but only while the engineer is still under
     * capacity. Returning false rather than overfilling is what lets the
     * assignment transaction detect that someone else took the last slot
     * first.
     */
    boolean incrementWorkload(Long engineerId);

    /**
     * Removes one from the workload, never going below zero.
     */
    boolean decrementWorkload(Long engineerId);

    /**
     * Brings the availability flag back in line with the workload: busy at
     * capacity, available below it.
     *
     * <p>The workload count is the authority on whether an engineer can take
     * more work, and both {@link #findAvailableFor} and
     * {@code NetworkEngineer.canAcceptWork()} test it directly, so this
     * changes nothing about who gets recommended. It exists because a roster
     * that lists a full engineer as "Available" reads as a bug to anybody
     * looking at it, and because {@code sp_assign_engineer} already keeps
     * the flag in step on the database side.</p>
     *
     * <p>Only AVAILABLE and BUSY are touched. On leave and off shift are set
     * by a manager and mean something this cannot know, so they are left
     * exactly as they are.</p>
     *
     * @return whether the flag actually moved, which is false both when it
     *         was already right and when the engineer is off duty
     */
    boolean refreshAvailability(Long engineerId);

    /**
     * Recalculates every engineer's active count from the tickets actually
     * open, repairing any drift.
     */
    int recalculateWorkloads();
}
