package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.CustomerDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.EscalationHistoryDAO;
import com.amdocs.telecom.dao.FeedbackDAO;
import com.amdocs.telecom.dao.LoginHistoryDAO;
import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.NetworkEventDAO;
import com.amdocs.telecom.dao.NotificationDAO;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.SLAConfigurationDAO;
import com.amdocs.telecom.dao.TelecomServiceDAO;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dao.UserDAO;

/**
 * The MySQL family of data access objects.
 *
 * <p>Each DAO is built once and shared. They keep no per-call state, and the
 * connection they use is decided by {@code ConnectionScope} at the moment of
 * the call, so one instance serves the console thread and every background
 * worker without contention.</p>
 */
public class MySQLDAOFactory extends DAOFactory {

    private final UserDAO userDAO = new UserDAOImpl();
    private final CustomerDAO customerDAO = new CustomerDAOImpl();
    private final TelecomServiceDAO telecomServiceDAO = new TelecomServiceDAOImpl();
    private final NetworkEngineerDAO networkEngineerDAO = new NetworkEngineerDAOImpl();
    private final SLAConfigurationDAO slaConfigurationDAO = new SLAConfigurationDAOImpl();
    private final TroubleTicketDAO troubleTicketDAO = new TroubleTicketDAOImpl();
    private final TicketStatusHistoryDAO ticketStatusHistoryDAO = new TicketStatusHistoryDAOImpl();
    private final EscalationHistoryDAO escalationHistoryDAO = new EscalationHistoryDAOImpl();
    private final NetworkEventDAO networkEventDAO = new NetworkEventDAOImpl();
    private final NotificationDAO notificationDAO = new NotificationDAOImpl();
    private final FeedbackDAO feedbackDAO = new FeedbackDAOImpl();
    private final AuditLogDAO auditLogDAO = new AuditLogDAOImpl();
    private final LoginHistoryDAO loginHistoryDAO = new LoginHistoryDAOImpl();
    private final ReportDAO reportDAO = new ReportDAOImpl();

    @Override
    public Vendor vendor() {
        return Vendor.MYSQL;
    }

    @Override
    public UserDAO getUserDAO() {
        return userDAO;
    }

    @Override
    public CustomerDAO getCustomerDAO() {
        return customerDAO;
    }

    @Override
    public TelecomServiceDAO getTelecomServiceDAO() {
        return telecomServiceDAO;
    }

    @Override
    public NetworkEngineerDAO getNetworkEngineerDAO() {
        return networkEngineerDAO;
    }

    @Override
    public SLAConfigurationDAO getSLAConfigurationDAO() {
        return slaConfigurationDAO;
    }

    @Override
    public TroubleTicketDAO getTroubleTicketDAO() {
        return troubleTicketDAO;
    }

    @Override
    public TicketStatusHistoryDAO getTicketStatusHistoryDAO() {
        return ticketStatusHistoryDAO;
    }

    @Override
    public EscalationHistoryDAO getEscalationHistoryDAO() {
        return escalationHistoryDAO;
    }

    @Override
    public NetworkEventDAO getNetworkEventDAO() {
        return networkEventDAO;
    }

    @Override
    public NotificationDAO getNotificationDAO() {
        return notificationDAO;
    }

    @Override
    public FeedbackDAO getFeedbackDAO() {
        return feedbackDAO;
    }

    @Override
    public AuditLogDAO getAuditLogDAO() {
        return auditLogDAO;
    }

    @Override
    public LoginHistoryDAO getLoginHistoryDAO() {
        return loginHistoryDAO;
    }

    @Override
    public ReportDAO getReportDAO() {
        return reportDAO;
    }
}
