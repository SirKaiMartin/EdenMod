package tel.eden.mod.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a newer EdenMod jar and swaps it in when the game closes.
 *
 * <p>The new jar is downloaded to a staging folder (<em>never</em> the mods folder,
 * so it can't be loaded a second time and cause a duplicate-mod crash). A JVM
 * shutdown hook then launches a small detached OS script that deletes the old jar
 * and moves the new one into the mods folder. On Windows the old jar is file-locked
 * while the game runs, so the script retries until the game has fully exited; on
 * Unix the swap applies immediately and simply takes effect on the next launch.
 *
 * <p>Failure is non-destructive: the staged jar lives outside the mods folder, and
 * the old jar is only removed once the swap actually runs, so a failed update never
 * leaves the mods folder with two EdenMod jars.
 */
public final class UpdateInstaller {
    private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
    private static final String MOD_ID = "edenmod";
    private static final int HTTP_OK = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Outcome of a download-and-stage attempt. */
    public enum Result {
        /** Downloaded and staged; the swap will run when the game closes. */
        SCHEDULED,
        /** The mod isn't running from a jar (dev env) — can't self-apply. */
        NOT_INSTALLED_FROM_JAR,
        /** The download or staging failed. */
        FAILED
    }

    /** Download {@code info}'s jar and arrange the on-close swap. */
    public Result downloadAndStage(UpdateInfo info) {
        Path oldJar = currentJar();
        if (oldJar == null || oldJar.getParent() == null) {
            return Result.NOT_INSTALLED_FROM_JAR;
        }
        try {
            Path stageDir = FabricLoader.getInstance().getGameDir().resolve("edenmod-update");
            Files.createDirectories(stageDir);
            Path staged = stageDir.resolve("edenmod-" + info.version() + ".jar");
            download(info.jarUrl(), staged);
            if (!Files.isRegularFile(staged) || Files.size(staged) <= 0) {
                return Result.FAILED;
            }
            Path newJar = oldJar.getParent().resolve("edenmod-" + info.version() + ".jar");
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> spawnSwapper(oldJar, staged, newJar, stageDir), "edenmod-apply"));
            return Result.SCHEDULED;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Update download/stage failed", e);
            return Result.FAILED;
        }
    }

    private void download(String url, Path target) throws IOException {
        try {
            HttpResponse<Path> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", "EdenMod-updater")
                            .timeout(Duration.ofMinutes(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofFile(
                            target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING));
            if (resp.statusCode() != HTTP_OK) {
                throw new IOException("download returned HTTP " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
    }

    private static Path currentJar() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID)
                    .flatMap(c -> c.getOrigin().getPaths().stream().findFirst())
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .orElse(null);
        } catch (RuntimeException e) {
            // Some origins (nested jars/dev classpath) don't expose a single path.
            return null;
        }
    }

    /** Write and launch the detached swap script (runs after the JVM has exited). */
    private void spawnSwapper(Path oldJar, Path staged, Path newJar, Path stageDir) {
        try {
            boolean windows =
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            if (windows) {
                Path bat = stageDir.resolve("apply-update.bat");
                Files.writeString(bat, ""
                        + "@echo off\r\n"
                        + ":retry\r\n"
                        + "del /f /q \"" + oldJar + "\" >nul 2>&1\r\n"
                        + "if exist \"" + oldJar + "\" (\r\n"
                        + "  ping -n 2 127.0.0.1 >nul\r\n"
                        + "  goto retry\r\n"
                        + ")\r\n"
                        + "move /y \"" + staged + "\" \"" + newJar + "\" >nul 2>&1\r\n"
                        + "del /f /q \"%~f0\" >nul 2>&1\r\n");
                new ProcessBuilder("cmd", "/c", "start", "", "/min", bat.toString()).start();
            } else {
                Path sh = stageDir.resolve("apply-update.sh");
                Files.writeString(sh, ""
                        + "#!/bin/sh\n"
                        + "while [ -e \"" + oldJar + "\" ]; do\n"
                        + "  rm -f \"" + oldJar + "\" 2>/dev/null\n"
                        + "  [ -e \"" + oldJar + "\" ] && sleep 1\n"
                        + "done\n"
                        + "mv -f \"" + staged + "\" \"" + newJar + "\"\n"
                        + "rm -f -- \"$0\"\n");
                sh.toFile().setExecutable(true);
                new ProcessBuilder("sh", sh.toString()).start();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to launch the update swap script", e);
        }
    }
}
