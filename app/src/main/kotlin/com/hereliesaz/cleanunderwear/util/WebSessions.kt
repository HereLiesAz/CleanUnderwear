package com.hereliesaz.cleanunderwear.util

import android.webkit.CookieManager

/**
 * Sign-ins made in the app's browser. The browser's cookie store is shared by every page it
 * opens later, and by the HTTP bridge that rides the user's session for lookup sites, so a
 * Facebook or Google sign-in made for one import is forgotten as soon as that import ends.
 */
object WebSessions {
    val FACEBOOK = listOf("facebook.com", "m.facebook.com", "mbasic.facebook.com")
    val INSTAGRAM = listOf("instagram.com", "www.instagram.com")
    val WHATSAPP = listOf("web.whatsapp.com", "whatsapp.com")
    val GOOGLE = listOf("google.com", "accounts.google.com", "contacts.google.com")

    /** Expires every cookie the browser holds for [hosts] (and their parent domains). */
    fun forget(hosts: List<String>) {
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
