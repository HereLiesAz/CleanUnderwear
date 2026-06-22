package com.hereliesaz.cleanunderwear.network

/**
 * Maps a Federal Bureau of Prisons facility name (the BOP inmate-locator
 * `faclName` field, e.g. "USP ATLANTA", "FCI BUTNER") to the US state the
 * facility sits in.
 *
 * This exists to support the *facility-state agreement* gate on the national
 * BOP search: a same-name federal record is only surfaced for review when the
 * facility's state matches the contact's resolved state.
 *
 * IMPORTANT CAVEATS — read before relying on this:
 *  - This is a **hand-maintained, best-effort** table. The BOP opens, closes,
 *    and renames institutions; new facilities will be absent until added here.
 *    When [stateFor] returns null the caller treats it as "cannot establish
 *    agreement" and does NOT surface the match (strict gate), so a gap here
 *    causes a *false negative* (a real incarceration is not flagged), not a
 *    false positive. Keep the table current to avoid silently missing matches.
 *  - Federal inmates are frequently held **out of their home state**, so even a
 *    correct lookup can legitimately disagree with the contact's state and
 *    suppress a true match. That tradeoff was chosen deliberately (strictest
 *    matching) — widen or remove the gate if it proves too aggressive.
 *
 * Matching is by city/location keyword: `faclName` is uppercased and scanned
 * for a known location token (longest token first so "TERRE HAUTE" wins over a
 * bare "HAUTE"). Keys are uppercase location names as they appear in faclName.
 */
object BopFacilityCatalog {

    /** Location keyword (uppercase) -> two-letter state code. */
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

    /**
     * Best-effort state code (e.g. "GA") for a BOP facility name, or null if the
     * facility isn't in the table. Null means the caller cannot establish
     * facility-state agreement.
     */
    fun stateFor(faclName: String?): String? {
        val s = faclName?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        return keysByLength.firstOrNull { s.contains(it) }?.let { locationToState[it] }
    }
}
