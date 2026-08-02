package tel.eden.mod.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import tel.eden.mod.EdenLogger;

/**
 * Downloads a newer EdenMod jar and swaps it in when the game closes.
 *
 * <p>The new jar is downloaded to a staging folder (<em>never</em> the mods folder,
 * so it can't be loaded a second time and cause a duplicate-mod crash). A JVM
 * shutdown hook then launches {@link UpdateApplier} as a small detached JVM (via
 * {@code javaw}/{@code java}, so no console window appears) that deletes the old jar
 * and copies the new one into the mods folder. On Windows the old jar is file-locked
 * while the game runs, so the helper retries until the game has fully exited; on Unix
 * the swap applies immediately and simply takes effect on the next launch.
 *
 * <p>The applier runs from a copy of the <em>current</em> jar (not the downloaded
 * one): a release older than this self-updater wouldn't contain {@link UpdateApplier},
 * and the old jar can't be its own classpath because it must stay deletable.
 *
 * <p>Failure is non-destructive: the staged jar lives outside the mods folder, and
 * the old jar is only removed once the swap actually runs, so a failed update never
 * leaves the mods folder with two EdenMod jars.
 */
public final class UpdateInstaller {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final String MOD_ID = "edenmod";
	private static final int HTTP_OK = 200;
	private static final String ATTESTATION_API = "https://api.github.com/repos/EdenGuild/EdenMod/attestations/sha256:";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

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
			sweepStaleStaging(stageDir);
			Path staged = stageDir.resolve("edenmod-" + info.version() + ".jar");
			download(info.jarUrl(), staged);
			if (!Files.isRegularFile(staged) || Files.size(staged) <= 0) {
				return Result.FAILED;
			}
			if (!verifyAttestation(staged, info.version())) {
				Files.deleteIfExists(staged);
				return Result.FAILED;
			}
			LOGGER.info("Update edenmod-{}.jar staged; will apply when the game closes", info.version());
			Path newJar = oldJar.getParent().resolve("edenmod-" + info.version() + ".jar");
			// The swap helper runs from a copy of the current jar, which is guaranteed
			// to contain UpdateApplier and is neither the (deletable) old jar nor the
			// staged jar. It is left behind and swept on the next launch.
			Path helper = stageDir.resolve("edenmod-helper.jar");
			Files.copy(oldJar, helper, StandardCopyOption.REPLACE_EXISTING);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> spawnSwapper(oldJar, staged, newJar, helper), "edenmod-apply"));
			return Result.SCHEDULED;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Update download/stage failed", e);
			return Result.FAILED;
		}
	}

	/**
	 * Verifies the downloaded jar against GitHub's artifact attestation API.
	 *
	 * <p>Computes the SHA-256 of the staged jar, then queries the GitHub attestation
	 * endpoint to confirm the jar was built by GitHub Actions from the EdenGuild/EdenMod
	 * repo. The DSSE payload is parsed to verify the attested subject hash matches the
	 * file we actually downloaded, and that the predicate references the correct repo.
	 * Full Sigstore certificate-chain verification is not performed — we trust GitHub's
	 * HTTPS API, which is the same trust boundary as downloading the release itself.
	 */
	private boolean verifyAttestation(Path jar, String version) {
		try {
			byte[] bytes = Files.readAllBytes(jar);
			String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

			HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(ATTESTATION_API + hex)).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "EdenMod-updater").timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());

			if (resp.statusCode() != HTTP_OK) {
				LOGGER.warn("Attestation API returned HTTP {} for edenmod-{}.jar", resp.statusCode(), version);
				return false;
			}

			JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
			JsonArray attestations = body.has("attestations") ? body.getAsJsonArray("attestations") : null;
			if (attestations == null || attestations.isEmpty()) {
				LOGGER.warn("No attestation found for edenmod-{}.jar (sha256={})", version, hex);
				return false;
			}

			// Decode the DSSE payload from the first attestation to verify the subject hash
			// and that the predicate references the correct repo.
			JsonObject bundle = attestations.get(0).getAsJsonObject().getAsJsonObject("bundle");
			String payloadB64 = bundle.getAsJsonObject("dsseEnvelope").get("payload").getAsString();
			String statement = new String(Base64.getDecoder().decode(payloadB64), StandardCharsets.UTF_8);
			JsonObject stmt = JsonParser.parseString(statement).getAsJsonObject();

			boolean hashMatched = false;
			if (stmt.has("subject") && stmt.get("subject").isJsonArray()) {
				for (var el : stmt.getAsJsonArray("subject")) {
					JsonObject digest = el.getAsJsonObject().getAsJsonObject("digest");
					if (digest != null && hex.equalsIgnoreCase(digest.has("sha256") ? digest.get("sha256").getAsString() : "")) {
						hashMatched = true;
						break;
					}
				}
			}
			if (!hashMatched) {
				LOGGER.warn("Attestation subject hash mismatch for edenmod-{}.jar (expected sha256={})", version, hex);
				return false;
			}

			// Confirm the statement references the correct repo (case-insensitive substring check
			// on the full statement JSON — works across all current GitHub predicate formats).
			if (!statement.toLowerCase(Locale.ROOT).contains("edenguild/edenmod")) {
				LOGGER.warn("Attestation predicate does not reference EdenGuild/EdenMod for edenmod-{}.jar", version);
				return false;
			}

			LOGGER.info("Attestation verified for edenmod-{}.jar (sha256={})", version, hex);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Attestation check interrupted for edenmod-{}.jar", version);
			return false;
		} catch (Exception e) {
			LOGGER.warn("Attestation check failed for edenmod-{}.jar", version, e);
			return false;
		}
	}

	private void download(String url, Path target) throws IOException {
		try {
			HttpResponse<Path> resp = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "EdenMod-updater").timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
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
			return FabricLoader.getInstance().getModContainer(MOD_ID).flatMap(c -> c.getOrigin().getPaths().stream().findFirst()).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).filter(Files::isRegularFile).orElse(null);
		} catch (RuntimeException e) {
			// Some origins (nested jars/dev classpath) don't expose a single path.
			return null;
		}
	}

	/** Launch the detached {@link UpdateApplier} JVM (runs on after this JVM exits). */
	private void spawnSwapper(Path oldJar, Path staged, Path newJar, Path helper) {
		try {
			ProcessBuilder pb = new ProcessBuilder(javaExecutable(), "-cp", helper.toString(), "tel.eden.mod.update.UpdateApplier", oldJar.toString(), staged.toString(), newJar.toString());
			// No inherited console (javaw already has none on Windows); discard streams
			// so the detached process never blocks on a full pipe.
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			pb.start();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to launch the update applier", e);
		}
	}

	/**
	 * The JVM launcher from the running JRE: {@code javaw} on Windows (so the swap
	 * pops up no console window), {@code java} elsewhere. Falls back to the bare name
	 * on the PATH if the expected binary isn't found under {@code java.home}.
	 */
	private static String javaExecutable() {
		Path bin = Path.of(System.getProperty("java.home", ""), "bin");
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		if (windows) {
			Path javaw = bin.resolve("javaw.exe");
			if (Files.isRegularFile(javaw)) {
				return javaw.toString();
			}
			Path java = bin.resolve("java.exe");
			return Files.isRegularFile(java) ? java.toString() : "javaw";
		}
		Path java = bin.resolve("java");
		return Files.isRegularFile(java) ? java.toString() : "java";
	}

	/** Best-effort removal of jars staged by an earlier (already-applied) update. */
	private static void sweepStaleStaging(Path stageDir) {
		try (java.util.stream.Stream<Path> files = Files.list(stageDir)) {
			files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					// Possibly still locked by an in-flight applier; leave it.
				}
			});
		} catch (IOException e) {
			LOGGER.debug("Could not sweep stale update staging", e);
		}
	}
}
