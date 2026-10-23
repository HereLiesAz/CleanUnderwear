---
layout: default
title: Privacy Policy
---

# CleanUnderwear Privacy Policy

CleanUnderwear helps you keep the contacts you already have up to date, and tells you when one of them appears in public arrest, inmate or obituary records. This policy describes exactly what the app reads, what it sends, and to whom.

## 1. What the app reads
- **Your device contacts** (`READ_CONTACTS`): names, phone numbers, email addresses, postal addresses, account type and the Notes field.
- **Messaging-app registration**: from your contacts (the same `READ_CONTACTS` permission), the Review screen checks which saved numbers WhatsApp, Signal, Telegram or Viber have registered, to tell a live number from a dead one. It reads only that a number is registered, in memory, and sends nothing anywhere. The app does not read your call log or text messages.
- **Accounts you choose to import from**: if you start an import from Settings, the app opens Facebook, WhatsApp Web, Instagram or Google Contacts in its own visible browser. You sign in there (or scan WhatsApp's QR code) if asked, and the app reads the names in your friends, chats, followers or contacts list. It does not read messages. Nothing is imported unless you start it.

## 2. What is stored, and where
- The app keeps its list of contacts (the Registry), their status, the records links and the matching text it found in a database on your device. It is not encrypted beyond Android's own app sandbox. Android backup and device transfer exclude it.
- When a contact's status settles (checked daily, or a status you confirmed: in custody, deceased or archived), the app writes that status, the date it was set and, for a confirmed status, the record link into a signed block in that contact's **Notes** field (`WRITE_CONTACTS`). Your own note text is kept. A possible match is never written. Contact notes sync with whatever account the contact belongs to, such as Google Contacts.
- Sign-ins you make for an import (Facebook, WhatsApp, Instagram, Google) are deleted from the app's browser when that import ends. A sign-in to a people-search site stays until you sign out in Settings.
- When contacts are merged as duplicates, the app keeps a copy of the merged-away entry (and the score that justified the merge) in the same database, so a wrong merge can be reviewed and undone.
- There is no CleanUnderwear server and no user account.

## 3. What leaves your device
To look someone up, the app has to send their details to the site being searched. The automatic searches send a contact's first and last name; when a common name fills the federal inmate locator's 100 results, it is asked again with the contact's middle name or approximate age, if known. Location is never in the request, but it decides which sites are asked: the state and county sources for the states a contact is tied to by ZIP code, phone area code or address. So which sites receive a name says roughly where the contact lives.
- **Federal inmate search** (daily, automatic): each US contact's name is sent to the Federal Bureau of Prisons inmate locator.
- **State prison searches** (daily, automatic): the public inmate search of each state the contact is tied to.
- **County jail rosters** (daily, automatic): the online jail rosters of counties in those states.
- **Court case searches** (daily, automatic): the public criminal case search of those states; only cases filed in the last 60 days are kept.
- **Obituary searches** (daily, automatic): each US contact's name on Echovita and ObitsArchive.
- **News search** (daily, automatic): each US and Canadian contact's name on Google News, together with words such as "obituary" and "arrested".
- **People-search** (only when you start it: Fill in details, a single contact's Look up details, or a search for a stale number's replacement): contacts without a known age, and unverified contacts, are looked up on CyberBackgroundChecks by phone number, email, address or name, to learn their age and confirm their details.
- **Links you open**: a contact's profile has links that search people-search, records, news, reverse-image and name-search sites. A link carries what it searches by: the contact's name, phone number, email or address (the news link sends name and address). Nothing is sent until you tap one.
- **Crash reports**: after a crash, the app asks on its next launch whether to send a report. If you agree, GitHub's new-issue page opens in your browser, filled in with the app and Android versions and where in the code it failed (exception types and stack frames, no messages and no contact data). You can read it and decide whether to post it; posted issues are public.
- **Update checks**: the request carries no contact data. Play installs ask Google Play; other installs ask GitHub's releases API.

Those sites receive the request under their own privacy policies. The app does not sell data, show ads, or include any analytics or advertising SDK.

## 4. Who is checked
Every contact with a usable full name who is tied to a US or Canadian state is checked, unless you archive them. Archive any contact by long-pressing it in the list. **Contact limit** in Settings checks and looks up only the first N contacts, by when they were added.

## 5. Accuracy
Matches are based on names, ages, places and the people an obituary names, and can be wrong. Nothing is recorded as fact until you confirm it. Every match shows the text it was based on, and you can dismiss it.

## 6. Deleting your data
Archive a contact to stop checking them. Settings → **Sign out of sites** removes your sign-ins in the app's browser. Settings → **Delete everything** deletes the app's database, removes the blocks it wrote into your contacts' notes (keeping your own note text), and signs you out of sites; your contacts themselves stay. Uninstalling the app deletes its database but leaves the note blocks in your contacts.

## 7. Contact
Questions: open an issue at [github.com/HereLiesAz/CleanUnderwear/issues](https://github.com/HereLiesAz/CleanUnderwear/issues).
