# Storage design

The app uses GitHub as the persistent mailbox archive/index, not the phone as the primary store.

## Repositories

### Yahoo-Mail-Search
This repository contains the Android app source code, build workflow, schemas, and documentation.

### Private mail-data repository
Actual mailbox data must be stored in a separate **private** GitHub repository. Do not store mailbox contents in the public app repository.

## Mail data format
- Mail is fetched from Yahoo over IMAP.
- Messages are normalized into encrypted data chunks before upload.
- Search metadata and message bodies are encrypted before they leave the device.
- Attachments are optional and can be archived separately.
- GitHub stores only ciphertext, never readable email content.

## Phone behavior
- The phone keeps only a working cache needed for current searches and viewing.
- The cache can be cleared without losing the GitHub archive.
- Search indexes can be downloaded/rebuilt from the encrypted GitHub data.
- Yahoo app password and GitHub credentials use Android secure credential storage.

## Deletion
- Search can identify matching messages across the GitHub archive.
- The user can preview matches before any destructive action.
- Move-to-Trash/Delete against Yahoo requires explicit confirmation.
- Deleting from Yahoo and deleting from the GitHub archive are separate actions, so an archived copy can be retained if desired.

## Important GitHub constraints
GitHub is not a mailbox database, so the app must avoid one-file-per-email commits and constant writes. Data should be batched into encrypted chunk files and updated in controlled sync batches.
