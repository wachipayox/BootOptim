package dev.wachipayox.bootoptim.bootstrap;

/** Runtime identity shared by the early bootstrap components. */
public final class BootOptimRuntimeInfo {
    public static final String RESOLVED_VERSION_PROPERTY = "boot_optim.version.resolved";

    private static final String VERSION = resolveVersion();

    private BootOptimRuntimeInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String resolveVersion() {
        String implementationVersion = BootOptimRuntimeInfo.class.getPackage().getImplementationVersion();
        String configuredVersion = System.getProperty("boot_optim.version");
        String version = nonBlank(implementationVersion)
                ? implementationVersion
                : nonBlank(configuredVersion) ? configuredVersion : "dev";
        System.setProperty(RESOLVED_VERSION_PROPERTY, version);
        return version;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
