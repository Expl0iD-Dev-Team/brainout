package com.desertkun.brainout;

public class Version
{
    public static final String VERSION = "valpha2";
    public static final String TAG = "master";

    /**
     * Anthill environment-service URL (the entry point to the online platform).
     * Overridable for a self-hosted backend via the {@code brainout.env_service}
     * system property or the {@code BRAINOUT_ENV_SERVICE} env var; otherwise the
     * official endpoint is used. (On Android, wire this through the online build.)
     */
    public static final String ENV_SERVICE = resolveEnvService();

    private static String resolveEnvService()
    {
        String prop = System.getProperty("brainout.env_service");
        if (prop != null && !prop.isEmpty()) return prop;

        String env = System.getenv("BRAINOUT_ENV_SERVICE");
        if (env != null && !env.isEmpty()) return env;

        return "https://env.brainout.org";
    }
}
