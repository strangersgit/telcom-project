package com.amdocs.telecom.controller;

import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;

import java.util.Optional;

/**
 * One numbered line on a dashboard: what it says, what it does, and who is
 * allowed to choose it.
 *
 * <h3>Why the permission lives here</h3>
 *
 * <p>The service layer already refuses an operation the role may not
 * perform, and that is where the rule is enforced. What it cannot do is
 * stop the option appearing on the screen in the first place, so a user
 * picks it, waits, and is told no. Declaring the permission alongside the
 * action means the menu can say so before anything is attempted, and the
 * two can never disagree: both consult the same {@link Permission}.</p>
 *
 * <p>This is belt and braces on purpose. Hiding a menu line is a courtesy,
 * not a security control; anyone reaching the service another way is still
 * refused there.</p>
 *
 * <h3>The action is a functional interface</h3>
 *
 * <p>So a dashboard reads as a list of what its options do, written as
 * lambdas and method references next to the labels they belong to, rather
 * than as a switch over integers with the bodies somewhere else.</p>
 */
public final class MenuOption {

    /**
     * What choosing an option does.
     *
     * <p>Deliberately not {@link Runnable}: this has nothing to do with
     * threads, and naming it for what it is keeps the two ideas apart in a
     * codebase that uses real {@code Runnable}s in the scheduler.</p>
     */
    @FunctionalInterface
    public interface MenuAction {
        void perform();
    }

    private final String label;

    /** Null when anyone who reached this dashboard may choose it. */
    private final Permission required;

    private final MenuAction action;
    private final boolean terminal;

    private MenuOption(String label, Permission required, MenuAction action, boolean terminal) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("A menu option needs a label");
        }
        if (action == null) {
            throw new IllegalArgumentException("A menu option needs something to do: " + label);
        }
        this.label = label.trim();
        this.required = required;
        this.action = action;
        this.terminal = terminal;
    }

    /** An option anyone on this dashboard may choose. */
    public static MenuOption of(String label, MenuAction action) {
        return new MenuOption(label, null, action, false);
    }

    /** An option only a role holding the permission may choose. */
    public static MenuOption guarded(String label, Permission required, MenuAction action) {
        if (required == null) {
            throw new IllegalArgumentException("A guarded option needs a permission: " + label);
        }
        return new MenuOption(label, required, action, false);
    }

    /**
     * The option that ends the menu, which every dashboard in sections 13
     * and 14 lists last.
     */
    public static MenuOption exit(String label) {
        return new MenuOption(label, null, () -> { }, true);
    }

    /** An exit option that does something on the way out, such as signing off. */
    public static MenuOption exit(String label, MenuAction action) {
        return new MenuOption(label, null, action, true);
    }

    public String getLabel() {
        return label;
    }

    public Optional<Permission> findRequiredPermission() {
        return Optional.ofNullable(required);
    }

    /** Whether choosing this option should close the dashboard. */
    public boolean isTerminal() {
        return terminal;
    }

    public boolean isAvailableTo(UserSession session) {
        return required == null || (session != null && session.hasPermission(required));
    }

    /**
     * Why the option is greyed out, for the line the user actually reads.
     */
    public String describeRefusal() {
        return required == null ? "That option is not available."
                : "Your role may not: " + required.getDescription().toLowerCase() + ".";
    }

    void perform() {
        action.perform();
    }

    @Override
    public String toString() {
        return label;
    }
}
