package tel.eden.mod.update;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Embedded GitHub Fulcio trust anchor used to verify GitHub artifact attestations.
 *
 * <p>Only the root cert is committed ({@code fulcio_root.b64} — Internal Services Root,
 * expires 2033-08-04). Intermediate certs (l1, l2) are fetched at runtime from
 * {@code fulcio.githubapp.com/api/v2/trustBundle}, verified against this root, and cached
 * in the user's {@code edenmod/} directory. This avoids the need to update the mod when
 * intermediates rotate (l2 rotates annually).
 *
 * <p>All expiry checks are purely local (wall clock vs. embedded cert {@code notAfter})
 * and cannot be induced by a network attacker.
 */
final class FulcioTrust {

	/** The GitHub Fulcio trust anchor — {@code Internal Services Root}, expires 2033-08-04. */
	static final X509Certificate ROOT;

	static {
		try {
			ROOT = loadCert("fulcio_root.b64");
		} catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private FulcioTrust() {
	}

	/** Returns {@code true} if the root cert has actually passed its {@code notAfter} date. */
	static boolean isRootExpired() {
		return System.currentTimeMillis() > ROOT.getNotAfter().getTime();
	}

	/**
	 * Returns {@code true} if the root cert expires within 180 days.
	 * Used by the Gradle {@code checkFulcioCert} task to fail CI early.
	 */
	static boolean isRootExpiringSoon() {
		return rootDaysUntilExpiry() < 180;
	}

	/**
	 * Days until the root cert expires. Negative when the cert has already expired.
	 * Used by the in-game expiry warnings.
	 */
	static long rootDaysUntilExpiry() {
		return (ROOT.getNotAfter().getTime() - System.currentTimeMillis()) / 86_400_000L;
	}

	private static X509Certificate loadCert(String resource) throws Exception {
		try (InputStream is = FulcioTrust.class.getResourceAsStream("/" + resource)) {
			if (is == null)
				throw new IllegalStateException("Missing classpath resource: " + resource + " — run ./gradlew updateFulcioCert");
			byte[] der = Base64.getDecoder().decode(new String(is.readAllBytes(), StandardCharsets.US_ASCII).trim());
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
		}
	}
}
