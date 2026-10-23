package com.hereliesaz.cleanunderwear.util

import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Sign-ins made in the app's browser. The browser's cookie store is shared by every page it
 * opens later, and by the HTTP bridge that rides the user's session for lookup sites, so a
 * Facebook or Google sign-in made for one import is forgotten as soon as that import ends:
 * its cookies, and the site's own storage (WhatsApp Web keeps its link to the phone there).
 */
object WebSessions {
    val FACEBOOK = listOf("facebook.com", "m.facebook.com", "mbasic.facebook.com")
    val INSTAGRAM = listOf("instagram.com", "www.instagram.com")
    val WHATSAPP = listOf("web.whatsapp.com", "whatsapp.com")
    val GOOGLE = listOf("google.com", "accounts.google.com", "contacts.google.com")

    /**
     * Expires every cookie the browser holds for [hosts] (and their parent domains) and deletes
     * those origins' web storage. Call on the main thread.
     */
    fun forget(hosts: List<String>) {
        val storage = WebStorage.getInstance()
        hosts.forEach { storage.deleteOrigin("https://$it") }
        val cookies = CookieManager.getInstance()
        for (host in hosts) {
            val url = "https://$host/"
            val names = cookies.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }.orEmpty()
            for (name in names) {
                for (domain in listOf(host, ".$host", ".${host.substringAfter('.')}")) {
                    cookies.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$domain; Path=/")
                }
                cookies.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
        }
        cookies.flush()
    }
}
