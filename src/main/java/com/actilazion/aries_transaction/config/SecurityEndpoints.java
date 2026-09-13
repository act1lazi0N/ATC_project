package com.actilazion.aries_transaction.config;

public final class SecurityEndpoints {
    public static final String REGISTER = "/api/v1/auth/register";
    public static final String LOGIN = "/api/v1/auth/login";
    public static final String REFRESH = "/api/v1/auth/refresh";
    public static final String LOGOUT = "/api/v1/auth/logout";
    public static final String EMAIL_VERIFICATION_CONFIRM = "/api/v1/auth/email-verification/confirm";
    public static final String FORGOT_PASSWORD = "/api/v1/auth/forgot-password";
    public static final String RESET_PASSWORD = "/api/v1/auth/reset-password";

    private SecurityEndpoints() {
    }
}
