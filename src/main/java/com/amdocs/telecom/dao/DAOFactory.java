package com.amdocs.telecom.dao;

import com.amdocs.telecom.dao.impl.MySQLDAOFactory;
import com.amdocs.telecom.exception.ConfigurationException;
import com.amdocs.telecom.exception.ErrorCode;

/**
 * Hands out data access objects without the caller naming an implementation.
 *
 * <p>This is the Factory pattern doing real work rather than ceremony. The
 * service layer asks for a {@code TroubleTicketDAO} and receives whatever
 * the configured database calls for; no service ever mentions MySQL, and
 * moving to another database would mean adding one subclass here and
 * changing nothing above it.</p>
 *
 * <p>The instance is created once through the holder idiom, which the JVM
 * makes thread safe without any locking of ours. The DAOs it returns hold no
 * conversation state, so sharing them across the background workers is
 * safe.</p>
 */
public abstract class DAOFactory {

    /**
     * Databases the application knows how to talk to.
     */
    public enum Vendor {
        MYSQL
    }

    private static final class Holder {
        private static final DAOFactory INSTANCE = forVendor(Vendor.MYSQL);
    }

    /**
     * The factory for the configured database.
     */
    public static DAOFactory getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Builds the factory for a named database, which is the seam a second
     * vendor would slot into.
     */
    public static DAOFactory forVendor(Vendor vendor) {
        if (vendor == Vendor.MYSQL) {
            return new MySQLDAOFactory();
        }
        throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                "No DAO factory is registered for database '" + vendor + "'");
    }

    /**
     * Which database this factory targets.
     */
    public abstract Vendor vendor();

    public abstract UserDAO getUserDAO();

    public abstract CustomerDAO getCustomerDAO();

    public abstract TelecomServiceDAO getTelecomServiceDAO();

    public abstract NetworkEngineerDAO getNetworkEngineerDAO();

    public abstract SLAConfigurationDAO getSLAConfigurationDAO();

    public abstract TroubleTicketDAO getTroubleTicketDAO();

    public abstract TicketStatusHistoryDAO getTicketStatusHistoryDAO();

    public abstract EscalationHistoryDAO getEscalationHistoryDAO();

    public abstract NetworkEventDAO getNetworkEventDAO();

    public abstract NotificationDAO getNotificationDAO();

    public abstract FeedbackDAO getFeedbackDAO();

    public abstract AuditLogDAO getAuditLogDAO();

    public abstract LoginHistoryDAO getLoginHistoryDAO();

    public abstract ReportDAO getReportDAO();
}
