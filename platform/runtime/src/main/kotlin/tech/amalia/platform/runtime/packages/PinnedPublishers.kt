package tech.amalia.platform.runtime.packages

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Host-supplied pins only. Package metadata cannot add or replace a trusted key. */
class PinnedPublishers(pins: Map<String, Map<String, PublicKey>> = emptyMap()) {
    private val pins = pins.mapValues { (_, keys) ->
        keys.mapValues { (keyId, key) ->
            require(keyId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid keyId" }
            requireP256(key)
            // Snapshot the encoded key rather than retaining a caller's mutable implementation.
            p256PublicKey(key.encoded)
        }
    }

    internal fun verify(publisher: String, manifest: ByteArray, envelope: ByteArray): PublisherIdentity {
        val value = PackageMetadata.objectValue(envelope)
        PackageMetadata.fields(value, setOf("algorithm", "keyId", "signature"))
        if (PackageMetadata.string(value, "algorithm") != "SHA256withECDSA") invalid("Unsupported signature algorithm")
        val keyId = PackageMetadata.string(value, "keyId")
        val key = pins[publisher]?.get(keyId) ?: invalid("Publisher signing key is not pinned")
        val encoded = PackageMetadata.string(value, "signature")
        val signature = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw PackageVerificationException("Invalid signature encoding", error)
        }
        if (signature.size !in 8..72 || Base64.getEncoder().encodeToString(signature) != encoded) {
            invalid("Signature must be canonical Base64 DER ECDSA")
        }
        try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(manifest)
            if (!verifier.verify(signature)) invalid("Publisher signature does not match manifest")
        } catch (error: PackageVerificationException) {
            throw error
        } catch (error: Exception) {
            throw PackageVerificationException("Cannot verify publisher signature", error)
        }
        return PublisherIdentity.PinnedSignature(publisher, keyId)
    }

    companion object {
        /** Decode an X.509 SubjectPublicKeyInfo DER pin, not a certificate or a private key. */
        fun p256PublicKey(der: ByteArray): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)).also(::requireP256)

        private fun requireP256(key: PublicKey) {
            require(key is ECPublicKey) { "An EC public key is required" }
            val expected = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            val actual = key.params
            require(actual.curve == expected.curve && actual.generator == expected.generator &&
                actual.order == expected.order && actual.cofactor == expected.cofactor) { "Only P-256 pins are supported" }
        }
    }
}
