package com.amdocs.telecom.security;

/**
 * A stored credential: the derived hash and the salt it was derived with.
 *
 * <p>Immutable, and deliberately without a {@code toString} that reveals
 * either value. The pair maps straight onto the {@code password_hash} and
 * {@code password_salt} columns.</p>
 */
public final class HashedPassword {

    private final String hash;
    private final String salt;

    HashedPassword(String hash, String salt) {
        this.hash = hash;
        this.salt = salt;
    }

    public String getHash() {
        return hash;
    }

    public String getSalt() {
        return salt;
    }

    /**
     * Describes the credential without disclosing it, for logs and reports.
     */
    @Override
    public String toString() {
        return "HashedPassword[algorithm=" + PasswordHasher.algorithmTagOf(hash)
                + ", hashLength=" + (hash == null ? 0 : hash.length())
                + ", saltLength=" + (salt == null ? 0 : salt.length()) + "]";
    }
}
