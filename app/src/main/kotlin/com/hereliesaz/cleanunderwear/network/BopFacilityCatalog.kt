package com.hereliesaz.cleanunderwear.network

/**
 * Resolves a Federal Bureau of Prisons facility to the US state it sits in, for
 * the national BOP search's facility-state agreement gate (a same-name federal
 * record is only surfaced when the facility's state matches the contact's).
 *
 * Resolution is a fallback chain ([stateFor]) so it is robust to the *string
 * format* the locator returns:
 *   1. the stable facility **code** (`faclCode`, e.g. "ATL") — format-invariant;
 *   2. a normalized **city/location keyword** in `faclName` (case-, punctuation-,
 *      and word-order-independent, so "USP ATLANTA", "Atlanta USP", and
 *      "U.S. Penitentiary, Atlanta, GA" all resolve);
 *   3. an explicit, validated **trailing state token** ("…, GA").
 *
 * IMPORTANT — what fallbacks can and cannot do:
 *  - String-format variation is fully handled by the chain above.
 *  - Geographic knowledge (that "Butner" is in NC) cannot be *derived* by any
 *    parsing — it must live in the tables below. The BOP institution set is
 *    finite, so [locationToState] aims to be complete; [codeToState] is a
 *    high-confidence, verified-only seed (a *wrong* code/state is worse than a
 *    missing one — a miss merely makes the strict gate suppress the match). New
 *    or renamed facilities are the only residual gap; verify additions against
 *    the official bop.gov facility list rather than guessing.
 */
object BopFacilityCatalog {

    /**
     * Authoritative two-letter US state/territory codes, used to validate a
     * trailing state token parsed out of a facility name (step 3). Mirrors the
     * `states` keys in `app/src/main/assets/sources.json`; kept inline so this
     * object stays pure (no Android context) and unit-testable.
     */
    private val usStateCodes: Set<String> = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI",
        "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN",
        "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH",
        "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA",
        "WV", "WI", "WY", "PR", "VI", "GU", "AS", "MP",
    )

    /**
     * Stable BOP facility code → state. Format-invariant and the most reliable
     * signal when present. Intentionally a small, high-confidence seed: codes
     * here must be verified against bop.gov. Anything not listed simply falls
     * through to name parsing, so a short table is safe (never wrong, only
     * less complete).
     */
    private val codeToState: Map<String, String> = mapOf(
        "ATL" to "GA", // USP Atlanta
    )

    /** City/location keyword (uppercase) → state. The primary resolver. */
    private val locationToState: Map<String, String> = mapOf(
        // Alabama
        "MONTGOMERY" to "AL", "TALLADEGA" to "AL", "ALICEVILLE" to "AL",
        // Arizona
        "TUCSON" to "AZ", "PHOENIX" to "AZ", "SAFFORD" to "AZ",
        // Arkansas
        "FORREST CITY" to "AR",
        // California
        "LOMPOC" to "CA", "VICTORVILLE" to "CA", "DUBLIN" to "CA",
        "ATWATER" to "CA", "MENDOTA" to "CA", "HERLONG" to "CA",
        "TERMINAL ISLAND" to "CA", "SAN DIEGO" to "CA", "LOS ANGELES" to "CA",
        // Colorado
        "FLORENCE" to "CO", "ENGLEWOOD" to "CO", "LITTLETON" to "CO",
        // Connecticut
        "DANBURY" to "CT",
        // Florida
        "COLEMAN" to "FL", "MIAMI" to "FL", "TALLAHASSEE" to "FL",
        "MARIANNA" to "FL", "PENSACOLA" to "FL",
        // Georgia
        "ATLANTA" to "GA", "JESUP" to "GA",
        // Hawaii
        "HONOLULU" to "HI",
        // Illinois
        "MARION" to "IL", "PEKIN" to "IL", "GREENVILLE" to "IL",
        "THOMSON" to "IL", "CHICAGO" to "IL",
        // Indiana
        "TERRE HAUTE" to "IN",
        // Kansas
        "LEAVENWORTH" to "KS",
        // Kentucky
        "ASHLAND" to "KY", "MANCHESTER" to "KY", "LEXINGTON" to "KY",
        "BIG SANDY" to "KY", "INEZ" to "KY",
        // Louisiana
        "POLLOCK" to "LA", "OAKDALE" to "LA", "NEW ORLEANS" to "LA",
        // Maryland
        "CUMBERLAND" to "MD",
        // Massachusetts
        "DEVENS" to "MA", "AYER" to "MA",
        // Michigan
        "MILAN" to "MI",
        // Minnesota
        "SANDSTONE" to "MN", "WASECA" to "MN", "ROCHESTER" to "MN", "DULUTH" to "MN",
        // Mississippi
        "YAZOO CITY" to "MS",
        // New Hampshire
        "BERLIN" to "NH",
        // New Jersey
        "FORT DIX" to "NJ", "FAIRTON" to "NJ",
        // New York
        "OTISVILLE" to "NY", "RAY BROOK" to "NY", "BROOKLYN" to "NY",
        // North Carolina
        "BUTNER" to "NC",
        // Ohio
        "ELKTON" to "OH", "LISBON" to "OH",
        // Oklahoma
        "EL RENO" to "OK", "OKLAHOMA CITY" to "OK",
        // Oregon
        "SHERIDAN" to "OR",
        // Pennsylvania
        "LORETTO" to "PA", "ALLENWOOD" to "PA", "WHITE DEER" to "PA",
        "LEWISBURG" to "PA", "CANAAN" to "PA", "WAYMART" to "PA",
        "MCKEAN" to "PA", "BRADFORD" to "PA", "SCHUYLKILL" to "PA",
        "MINERSVILLE" to "PA", "PHILADELPHIA" to "PA",
        // Puerto Rico
        "GUAYNABO" to "PR",
        // South Carolina
        "ESTILL" to "SC", "BENNETTSVILLE" to "SC", "EDGEFIELD" to "SC",
        "WILLIAMSBURG" to "SC",
        // South Dakota
        "YANKTON" to "SD",
        // Tennessee
        "MEMPHIS" to "TN",
        // Texas
        "BIG SPRING" to "TX", "BEAUMONT" to "TX", "BASTROP" to "TX",
        "SEAGOVILLE" to "TX", "THREE RIVERS" to "TX", "FORT WORTH" to "TX",
        "LA TUNA" to "TX", "ANTHONY" to "TX", "TEXARKANA" to "TX",
        "HOUSTON" to "TX", "DALLAS" to "TX",
        // Virginia
        "PETERSBURG" to "VA", "JONESVILLE" to "VA",
        // Washington
        "SEATAC" to "WA", "SEATTLE" to "WA",
        // West Virginia
        "HAZELTON" to "WV", "BRUCETON MILLS" to "WV", "BECKLEY" to "WV",
        "GILMER" to "WV", "GLENVILLE" to "WV", "MORGANTOWN" to "WV",
        // Wisconsin
        "OXFORD" to "WI",
    )

    // Longest keys first so multi-word locations win over substrings.
    private val keysByLength: List<String> = locationToState.keys.sortedByDescending { it.length }

    /** Convenience overload for callers that only have the display name. */
    fun stateFor(faclName: String?): String? = stateFor(faclCode = null, faclName = faclName)

    /**
     * Best-effort state code (e.g. "GA") for a BOP facility, or null when it
     * can't be resolved (caller treats null as "cannot establish agreement").
     */
    fun stateFor(faclCode: String?, faclName: String?): String? {
        // 1. Stable facility code — format-invariant, most reliable.
        faclCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.let { code ->
            codeToState[code]?.let { return it }
        }

        // Normalize the display name: uppercase, every non-alphanumeric run to a
        // single space, trimmed. Makes matching punctuation- and order-agnostic.
        val norm = faclName
            ?.uppercase()
            ?.replace(Regex("[^A-Z0-9]+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // 2. City/location keyword, matched on whole-word boundaries.
        val padded = " $norm "
        keysByLength.firstOrNull { padded.contains(" $it ") }
            ?.let { return locationToState[it] }

        // 3. Explicit trailing state token, validated against the real code set.
        norm.substringAfterLast(' ').takeIf { it in usStateCodes }?.let { return it }

        return null
    }
}
