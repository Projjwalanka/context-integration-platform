package com.bank.aiassistant.context;

/**
 * Thread-local tenant holder. Uses InheritableThreadLocal so async tasks
 * spawned from a request thread (e.g. @Async) carry the same tenant ID.
 *
 * Tenant is derived from the authenticated user's email domain:
 *   admin@bank.com  →  bank.com
 *   alice@acme.org  →  acme.org
 */
public final class TenantContext {

    private static final InheritableThreadLocal<String> CURRENT =
            new InheritableThreadLocal<>();

    private TenantContext() {}

    public static String get() {
        String t = CURRENT.get();
        return t != null ? t : "default";
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId != null ? tenantId.toLowerCase() : "default");
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Derives tenant ID from an email address. */
    public static String fromEmail(String email) {
        if (email == null || !email.contains("@")) return "default";
        return email.substring(email.indexOf('@') + 1).toLowerCase();
    }
}
