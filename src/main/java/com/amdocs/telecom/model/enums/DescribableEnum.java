package com.amdocs.telecom.model.enums;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Contract shared by every domain enum in the system.
 *
 * <p>Each constant carries a short persistence code and a human readable label,
 * which keeps database values compact while console output stays legible.</p>
 *
 * <p>The {@code default} method supplies shared formatting to every enum
 * without duplication, and the {@code static} methods provide a single generic
 * lookup used by the whole DAO layer when mapping a {@code ResultSet} column
 * back to a typed constant.</p>
 */
public interface DescribableEnum {

    /**
     * Short stable token persisted in the database.
     */
    String getCode();

    /**
     * Label shown to the user on the console.
     */
    String getDisplayName();

    /**
     * Combined form used across menus and reports.
     */
    default String describe() {
        return getCode() + " - " + getDisplayName();
    }

    /**
     * Resolves a constant by its persistence code, ignoring case and padding.
     *
     * @param enumType the enum class to search
     * @param code     the stored code, possibly {@code null}
     * @return the matching constant, or empty when nothing matches
     */
    static <E extends Enum<E> & DescribableEnum> Optional<E> fromCode(Class<E> enumType, String code) {
        if (code == null || code.trim().isEmpty()) {
            return Optional.empty();
        }
        final String target = code.trim();
        return Arrays.stream(enumType.getEnumConstants())
                .filter(constant -> constant.getCode().equalsIgnoreCase(target))
                .findFirst();
    }

    /**
     * Resolves a constant by its declared name, ignoring case and padding.
     */
    static <E extends Enum<E> & DescribableEnum> Optional<E> fromName(Class<E> enumType, String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        final String target = name.trim();
        return Arrays.stream(enumType.getEnumConstants())
                .filter(constant -> constant.name().equalsIgnoreCase(target))
                .findFirst();
    }

    /**
     * Renders every constant as a numbered list for console menus.
     */
    static <E extends Enum<E> & DescribableEnum> String asMenu(Class<E> enumType) {
        E[] constants = enumType.getEnumConstants();
        return Arrays.stream(constants)
                .map(constant -> String.format("%2d. %s", constant.ordinal() + 1, constant.getDisplayName()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * Returns the constant at a one based menu position.
     */
    static <E extends Enum<E> & DescribableEnum> Optional<E> fromMenuChoice(Class<E> enumType, int choice) {
        E[] constants = enumType.getEnumConstants();
        if (choice < 1 || choice > constants.length) {
            return Optional.empty();
        }
        return Optional.of(constants[choice - 1]);
    }
}
