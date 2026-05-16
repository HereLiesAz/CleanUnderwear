package com.hereliesaz.cleanunderwear.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import com.hereliesaz.cleanunderwear.network.SourceCatalog
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls contacts out of the system contact provider and projects them onto
 * [Target] rows. URLs parsed from contact notes (`Records:` / `Obit:`) are
 * whitelisted against [SourceCatalog] before being trusted, so legacy notes
 * containing search-engine URLs from earlier app versions can't re-poison the
 * registry.
 */
@Singleton
class ContactHarvester @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceCatalog: SourceCatalog
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    suspend fun harvestContacts(allowedAccountTypes: Set<String> = emptySet()): List<Target> = withContext(Dispatchers.IO) {
        val contactDataMap = mutableMapOf<Long, ContactData>()
        // Per-run cache so the same off-catalog URL on every contact note
        // doesn't produce N identical "Ignoring non-catalog ..." log lines.
        val loggedNonCatalogUrls = mutableSetOf<String>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE
        )

        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            "vnd.android.cursor.item/vnd.com.whatsapp.profile",
            "vnd.android.cursor.item/vnd.facebook.profile"
        )

        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?, ?, ?, ?)"

        val cursor: Cursor? = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        val rowCount = cursor?.count ?: 0
        DiagnosticLogger.log("Scything system contacts: Found $rowCount raw records.")

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimeTypeIndex = it.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val data1Index = it.getColumnIndex(ContactsContract.Data.DATA1)
            val nameIndex = it.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
            val accountTypeIndex = it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val data7Index = it.getColumnIndex(ContactsContract.Data.DATA7) // City
            val data8Index = it.getColumnIndex(ContactsContract.Data.DATA8) // Region
            val data9Index = it.getColumnIndex(ContactsContract.Data.DATA9) // Postcode

            while (it.moveToNext()) {
                val contactId = if (idIndex >= 0) it.getLong(idIndex) else -1L
                if (contactId <= 0) continue

                val mimeType = if (mimeTypeIndex >= 0) it.getString(mimeTypeIndex) else continue
                val displayName = if (nameIndex >= 0) it.getString(nameIndex) ?: "Unnamed Entity" else "Unnamed Entity"
                
                val accountType = if (accountTypeIndex >= 0) it.getString(accountTypeIndex) else null

                val contactData = contactDataMap.getOrPut(contactId) { ContactData(displayName) }

                val mappedAccount = when {
                    accountType?.contains("google", ignoreCase = true) == true -> "Google"
                    accountType?.contains("facebook", ignoreCase = true) == true || 
                    accountType?.contains("whatsapp", ignoreCase = true) == true -> "Meta"
                    accountType?.contains("apple", ignoreCase = true) == true -> "Apple"
                    else -> "Device"
                }

                if (allowedAccountTypes.isEmpty() || allowedAccountTypes.contains(mappedAccount)) {
                    contactData.accounts.add(mappedAccount)
                } else {
                    continue
                }

                when (mimeType) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val number = it.getString(data1Index)
                        if (number != null && contactData.phoneNumber == null) {
                            contactData.phoneNumber = number
                        }
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val city = it.getString(data7Index)
                        val region = it.getString(data8Index)
                        val zip = it.getString(data9Index)

                        val locationParts = listOfNotNull(city, region, zip).filter { part -> part.isNotBlank() }
                        if (locationParts.isNotEmpty() && contactData.residenceInfo == null) {
                            contactData.residenceInfo = locationParts.joinToString(", ")
                        }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        val note = it.getString(data1Index) ?: ""
                        parseNoteMetadata(note, contactData, loggedNonCatalogUrls)
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val email = it.getString(data1Index)
                        if (email != null && contactData.email == null) {
                            contactData.email = email
                        }
                    }
                }
            }
        }
        
        val targets = contactDataMap.values.mapNotNull { data ->
            val number = data.phoneNumber
            val email = data.email
            
            // Allow email-only or phone-only contacts
            if (number == null && email == null) return@mapNotNull null
            
            val cleanNumber = number?.filter { char -> char.isDigit() }
            
            if (cleanNumber != null) {
                // Filter out clearly non-person entities (short codes, etc.)
                // We allow 7-digit local numbers and 10+ digit full numbers.
                if (cleanNumber.length < 7 || cleanNumber.length in 8..9) return@mapNotNull null
            }

            val areaCode = when {
                cleanNumber == null -> null
                cleanNumber.length >= 10 -> cleanNumber.takeLast(10).take(3)
                else -> "LOCAL"
            }

            val sourceAccounts = if (data.accounts.isEmpty()) {
                "Device Contacts"
            } else {
                data.accounts.joinToString(", ")
            }

            Target(
                displayName = data.displayName,
                phoneNumber = number,
                email = email,
                areaCode = areaCode,
                sourceAccount = sourceAccounts,
                residenceInfo = data.residenceInfo,
                status = data.status,
                lockupUrl = data.lockupUrl,
                obituaryUrl = data.obituaryUrl,
                lastScrapedTimestamp = data.lastCheckTimestamp
            )
        }

        Log.d("ContactHarvester", "Harvested ${targets.size} raw targets")
        targets
    }

    private fun parseNoteMetadata(
        note: String,
        data: ContactData,
        loggedNonCatalogUrls: MutableSet<String>,
    ) {
        val statusMatch = "\\[Registry Status: (.*?)\\]".toRegex().find(note)
        statusMatch?.let {
            data.status = when (it.groupValues[1]) {
                "Monitoring" -> TargetStatus.MONITORING
                "Unverified" -> TargetStatus.UNVERIFIED
                "Incarcerated" -> TargetStatus.INCARCERATED
                "Deceased" -> TargetStatus.DECEASED
                "Archived" -> TargetStatus.IGNORED
                else -> TargetStatus.UNKNOWN
            }
        }

        val lastCheckMatch = "Last Check: (.*?)(\n|$)".toRegex().find(note)
        lastCheckMatch?.let {
            val raw = it.groupValues[1]
            try {
                val date = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).parse(raw)
                data.lastCheckTimestamp = date?.time ?: 0L
            } catch (e: Exception) {
                DiagnosticLogger.log("Unparseable Last Check date in contact note: '$raw'")
            }
        }

        val recordsMatch = "Records: (.*?)(\n|$)".toRegex().find(note)
        recordsMatch?.let {
            val url = it.groupValues[1].trim()
            if (sourceCatalog.isFromCatalog(url)) {
                data.lockupUrl = url
            } else if (url.isNotBlank() && loggedNonCatalogUrls.add("records|$url")) {
                DiagnosticLogger.log("Ignoring non-catalog Records URL on contact note: $url")
            }
        }

        val obitMatch = "Obit: (.*?)(\n|$)".toRegex().find(note)
        obitMatch?.let {
            val url = it.groupValues[1].trim()
            if (sourceCatalog.isFromCatalog(url)) {
                data.obituaryUrl = url
            } else if (url.isNotBlank() && loggedNonCatalogUrls.add("obit|$url")) {
                DiagnosticLogger.log("Ignoring non-catalog Obit URL on contact note: $url")
            }
        }
    }

    private class ContactData(
        val displayName: String,
        var phoneNumber: String? = null,
        var email: String? = null,
        var residenceInfo: String? = null,
        val accounts: MutableSet<String> = mutableSetOf(),
        var status: TargetStatus = TargetStatus.UNKNOWN,
        var lastCheckTimestamp: Long = 0L,
        var lockupUrl: String? = null,
        var obituaryUrl: String? = null
    )
}

