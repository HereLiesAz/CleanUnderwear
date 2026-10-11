---
layout: default
title: Privacy Policy
---

# CleanUnderwear Privacy Policy

CleanUnderwear helps you keep the contacts you already have up to date, and tells you when one of them appears in public arrest, inmate or obituary records. This policy describes exactly what the app reads, what it sends, and to whom.

## 1. What the app reads
- **Your device contacts** (`READ_CONTACTS`): names, phone numbers, email addresses, postal addresses, account type and the Notes field.
- **Accounts you choose to import from**: if you start an import from Settings, the app opens Facebook, WhatsApp Web, Instagram or Google Contacts in an in-app browser, using your own session, and reads the names in your friends, chats, followers or contacts list. Nothing is imported unless you start it.

## 2. What is stored, and where
- The app keeps its list of contacts (the Registry), their status, the records links and the matching text it found in a database on your device. It is not encrypted beyond Android's own app sandbox.
- When a contact's status changes, the app writes the status, record links and check date into that contact's **Notes** field (`WRITE_CONTACTS`). Contact notes sync with whatever account the contact belongs to, such as Google Contacts.
- There is no CleanUnderwear server and no user account.

## 3. What leaves your device
To look someone up, the app has to send their details to the site being searched:
- **Public records** (daily, automatic): a contact's name, and where known a city, ZIP or area code, is sent to public inmate, arrest and obituary sources, including the Federal Bureau of Prisons inmate locator.
- **People-search** (only when you ask): when you look up missing details for a contact, their name, phone number or email is sent to CyberBackgroundChecks.
- **Links you open**: profile source chips open search sites (records sites, obituary sites, reverse-image and name-search services) in your browser with the contact's name in the address. Nothing is sent until you tap one.
- **Crash reports**: if the app crashes, the error message, stack trace, device model and Android version are posted as an issue in the app's public GitHub repository.

Those sites receive the request under their own privacy policies. The app does not sell data, show ads, or include any analytics or advertising SDK.

## 4. Who is checked
Every contact the app imports is checked unless you archive them. You can archive any contact by long-pressing it in the list, and set a limit on how many contacts are monitored in Settings.

## 5. Accuracy
Matches are based on names and locations, and can be wrong. Every status change shows the text it was based on so you can verify it, and you can dismiss a match.

## 6. Deleting your data
Archive a contact to stop checking them. Uninstalling the app deletes its database. Notes the app wrote to your contacts stay in your contacts until you remove them.

## 7. Contact
Questions: open an issue at [github.com/HereLiesAz/CleanUnderwear/issues](https://github.com/HereLiesAz/CleanUnderwear/issues).
