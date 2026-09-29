package com.amdocs.telecom.security;

import com.amdocs.telecom.util.AppConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * The rules a password has to satisfy before it can be set.
 *
 * <p>Each rule is a predicate paired with the sentence shown when it fails,
 * and every rule is evaluated on every submission. Reporting all the problems
 * at once is the difference between one correction and four.</p>
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
        throw new AssertionError("PasswordPolicy is not instantiable");
    }

    /**
     * Passwords common enough that they are tried first in any attack, so
     * they are refused however well they satisfy the character rules.
     */
    private static final List<String> REFUSED = Collections.unmodifiableList(Arrays.asList(
            "password", "password1", "passw0rd", "12345678", "123456789",
            "qwerty123", "letmein", "welcome1", "admin123", "tsatms123"));

    /** A rule: what it demands of a password, and what to say when it is not met. */
    private static final class Rule {
        private final BiPredicate<char[], String> satisfied;
        private final String complaint;

        private Rule(BiPredicate<char[], String> satisfied, String complaint) {
            this.satisfied = satisfied;
            this.complaint = complaint;
        }
    }

    private static final List<Rule> RULES = buildRules();

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        rules.add(new Rule(
                (password, username) -> password.length >= AppConstants.MIN_PASSWORD_LENGTH,
                "must be at least " + AppConstants.MIN_PASSWORD_LENGTH + " characters long"));

        rules.add(new Rule(
                (password, username) -> containsMatch(password, Character::isUpperCase),
                "must contain an upper case letter"));

        rules.add(new Rule(
                (password, username) -> containsMatch(password, Character::isLowerCase),
                "must contain a lower case letter"));

        rules.add(new Rule(
                (password, username) -> containsMatch(password, Character::isDigit),
                "must contain a digit"));

        rules.add(new Rule(
                (password, username) -> containsMatch(password,
                        character -> !Character.isLetterOrDigit(character)
                                && !Character.isWhitespace(character)),
                "must contain a symbol such as @ # $ or !"));

        rules.add(new Rule(
                (password, username) -> !containsMatch(password, Character::isWhitespace),
                "must not contain spaces or tabs"));

        rules.add(new Rule(
                (password, username) -> username == null
                        || !username.equalsIgnoreCase(new String(password)),
                "must not be the same as the username"));

        rules.add(new Rule(
                (password, username) -> username == null
                        || username.length() < 4
                        || !new String(password).toLowerCase().contains(username.toLowerCase()),
                "must not contain the username"));

        rules.add(new Rule(
                (password, username) -> !REFUSED.contains(new String(password).toLowerCase()),
                "is too widely used to be accepted"));

        return Collections.unmodifiableList(rules);
    }

    /**
     * Every rule the password fails, in the order the rules are declared.
     * An empty list means the password is acceptable.
     */
    public static List<String> violations(char[] password, String username) {
        if (password == null || password.length == 0) {
            return Collections.singletonList("must not be blank");
        }
        return RULES.stream()
                .filter(rule -> !rule.satisfied.test(password, username))
                .map(rule -> rule.complaint)
                .collect(Collectors.toList());
    }

    public static boolean isAcceptable(char[] password, String username) {
        return violations(password, username).isEmpty();
    }

    /**
     * The rules written out for the console, so the requirements are visible
     * before the user types rather than only after they get it wrong.
     */
    public static String describeRules() {
        return "Password must be at least " + AppConstants.MIN_PASSWORD_LENGTH
                + " characters and include an upper case letter, a lower case"
                + " letter, a digit and a symbol.";
    }

    /**
     * Java 8 has no stream over a {@code char[]}, and boxing every character
     * to use one would allocate for nothing.
     */
    private static boolean containsMatch(char[] password, CharacterTest test) {
        for (char character : password) {
            if (test.matches(character)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes a primitive {@code char}, which lets the rules above be written
     * as method references to {@link Character} without boxing.
     */
    @FunctionalInterface
    private interface CharacterTest {
        boolean matches(char character);
    }
}
