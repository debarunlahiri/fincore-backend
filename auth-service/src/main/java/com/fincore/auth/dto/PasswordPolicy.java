package com.fincore.auth.dto;

/**
 * One password rule for registration, staff creation and password change: 8 to 72 characters with
 * upper case, lower case and a digit.
 */
public final class PasswordPolicy {

    public static final String REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,72}$";
    public static final String MESSAGE =
            "must be 8 to 72 characters with an upper case letter, a lower case letter and a digit";

    private PasswordPolicy() {}
}
