# Yahoo Mail Search — Privacy Policy Draft

Last updated: September 28, 2026

## Overview
Yahoo Mail Search is designed to help users search, archive, organize, clean, and monitor email in accounts they connect.

## Data the app accesses
The app may access:
- Yahoo email address
- Yahoo app password supplied by the user
- Email sender, recipient, subject, body text, dates, folders, and attachment filenames
- A GitHub personal access token supplied by the user
- An archive encryption passphrase supplied by the user
- Saved alert rules and app preferences

## How data is used
Email data is used only to provide the app's search, archive, cleanup, and alert features.

## Storage
Credentials are stored on the user's Android device using Android Keystore protected encryption.

When GitHub archive sync is enabled, email archive data is encrypted on-device before upload to the user's configured GitHub repository. The archive passphrase is required to decrypt the archive.

The app also maintains a temporary on-device search cache. The user can clear that cache from Settings and rebuild it from the encrypted archive.

## Sharing
The app does not sell email content or credentials to advertisers.

The current product design does not require third-party advertising trackers. Monetization is planned through an optional Google Play Pro subscription.

## Notifications
If the user creates alert rules, the app may periodically connect to Yahoo to check for new matching emails. Users can hide sender and subject details from lock-screen notifications.

## User controls
Users can:
- Disable individual alert rules
- Disable background archive sync
- Clear the local search cache
- Delete Yahoo mail through explicit confirmed actions
- Revoke the Yahoo app password in Yahoo Account Security
- Revoke the GitHub token in GitHub settings
- Delete the encrypted GitHub archive from their own repository

## Security
The app uses TLS for network connections, Android Keystore for locally protected credentials, and AES-256-GCM for the GitHub mail archive.

## Account deletion
The app does not create a separate developer-hosted user account in the current version. Users control their Yahoo and GitHub accounts directly with those providers.

## Public release note
Before Google Play publication, replace this draft with the final developer identity/contact details and host the final policy at a stable public HTTPS URL.
