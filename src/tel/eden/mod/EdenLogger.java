package tel.eden.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared logger for all edenmod classes.
 *
 * <p>Every message is automatically prefixed with {@code [edenmod]} so lines are easy to find in
 * the Minecraft launcher log (which does not display the SLF4J logger name). All classes obtain
 * the singleton via {@link #get()} and keep a {@code LOGGER} field of this type — call sites are
 * identical to a plain SLF4J {@code Logger}.
 *
 * <p>Throwable handling mirrors SLF4J: if the last argument in a varargs call is a {@code
 * Throwable} and the format string has no remaining {@code {}} placeholder for it, SLF4J extracts
 * it as the logged cause.
 */
public final class EdenLogger {
	private static final EdenLogger INSTANCE = new EdenLogger();
	private static final Logger DELEGATE = LoggerFactory.getLogger("edenmod");
	private static final String PFX = "[EdenMod] ";

	private EdenLogger() {
	}

	public static EdenLogger get() {
		return INSTANCE;
	}

	public void info(String format, Object... args) {
		DELEGATE.info(PFX + format, args);
	}

	public void warn(String format, Object... args) {
		DELEGATE.warn(PFX + format, args);
	}

	public void debug(String format, Object... args) {
		DELEGATE.debug(PFX + format, args);
	}

	public void error(String format, Object... args) {
		DELEGATE.error(PFX + format, args);
	}
}
