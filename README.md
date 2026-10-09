# Yahoo Mail Search

Android app for searching, archiving, and safely cleaning a large Yahoo mailbox.

## Storage model

- Yahoo remains the live mailbox.
- The private GitHub repository `helpsomeoneonline-hub/Yahoo-Mail-Data` is the long-term encrypted archive.
- Email content is compressed and encrypted with AES-256-GCM before upload.
- The archive encryption key is derived from a user-chosen passphrase with PBKDF2-HMAC-SHA256.
- The phone keeps only a temporary search cache under Android's cache storage. It can be cleared without deleting the GitHub archive.
- Yahoo passwords, Yahoo app passwords, GitHub tokens, and archive passphrases are never committed to GitHub.

## Current v0.1 features

- Connect to Yahoo IMAP over SSL.
- Archive non-Spam/non-Trash Yahoo folders in encrypted chunks of up to 100 messages.
- Resume synchronization using encrypted UID checkpoints stored in the private data repository.
- Search sender, recipients, subject, body text, and attachment filenames.
- Rebuild the temporary search cache from the encrypted GitHub archive.
- Bulk move every message matching a search to Yahoo Trash after an explicit confirmation.
- Keep the encrypted GitHub archive copy when Yahoo mail is moved to Trash.
- Build a debug APK with GitHub Actions.

## App setup

The app asks for:

1. Yahoo email address.
2. Yahoo app password.
3. A GitHub token that has read/write Contents access to the private `Yahoo-Mail-Data` repository.
4. An archive passphrase of at least 8 characters.

The connection values are stored locally using Android Keystore encryption. Keep a separate safe copy of the archive passphrase because it is required to decrypt the GitHub archive on another device.

## Build

Run the **Build Android APK** workflow in GitHub Actions. The workflow uploads `Yahoo-Mail-Search-debug` as an artifact containing `app-debug.apk`.

## Important

The first synchronization of a very large mailbox can take many network operations. Attachments are indexed by filename in v0.1, but attachment file bytes are not archived yet.


## Easier one-time Yahoo login (v2.0.1)

- On first launch, enter your Yahoo email address (if not prefilled), Yahoo **app password**, and Google Sheet URL, then tap **Test & Save Connections**.
- The app encrypts your credentials using Android Keystore. On later launches it hides the login fields, shows the saved Yahoo account, and lets you tap **Check Last 5 Days Now** without retyping credentials.
- Use **Change Yahoo account or app password** to update a password or switch accounts.
- Optional first-run email prefill: add the GitHub **repository variable** `YAHOO_EMAIL` under Settings → Secrets and variables → Actions → Variables, then rebuild. Email addresses baked into APKs are readable to anyone with the APK.
- A GitHub Actions secret called `YAHOO_APP_PASSWORD` is not accessible to the installed Android app. **Never put this password into the APK or source repository.** Enter it once on the phone.
- When Android removes app data or the app is reinstalled, locally saved credentials may need to be entered again.
