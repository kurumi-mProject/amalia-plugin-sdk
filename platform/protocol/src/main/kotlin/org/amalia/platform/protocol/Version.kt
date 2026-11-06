package org.amalia.platform.protocol

import kotlinx.serialization.Serializable

/** Stable numeric versions only; ordering is lexicographic, never floating point. */
@Serializable
data class Version(val major: Int, val minor: Int = 0, val patch: Int = 0) : Comparable<Version> {
    init { require(major >= 0 && minor >= 0 && patch >= 0) }
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): Version {
            require(Regex("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){0,2}").matches(text)) {
                "Expected one to three non-negative version components"
            }
            val parts = text.split('.').map(String::toInt)
            return Version(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
        }
    }
}

/** Inclusive lower bound, exclusive upper bound. No implicit major-version compatibility. */
@Serializable
data class VersionRange(val minimum: Version, val maximumExclusive: Version? = null) {
    init { require(maximumExclusive == null || maximumExclusive > minimum) }
    operator fun contains(version: Version): Boolean =
        version >= minimum && (maximumExclusive == null || version < maximumExclusive)
}

object Protocol {
    val version: Version = Version(1)
    val supportedVersions: VersionRange = VersionRange(version, Version(2))
}
