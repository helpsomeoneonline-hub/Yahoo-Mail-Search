# Storage design

The app stores mailbox data on the Android device, not in GitHub.

## On-device data
- Room/SQLite database for message metadata and searchable text.
- Full message bodies can be cached locally.
- Attachments are downloaded only when requested unless the user enables offline attachment caching.
- Yahoo credentials/app passwords are stored using Android secure credential storage, never in the repository.

## Yahoo synchronization
- Yahoo remains the authoritative mailbox.
- The local database is an indexed copy for fast search.
- Move-to-Trash/Delete actions are performed against Yahoo and then reflected locally.
- A future "Keep local archive after server delete" option can preserve selected messages on the phone.

## GitHub repository
GitHub contains only:
- Android source code
- Database schema/migrations
- Build workflow
- Documentation

It must never contain:
- User email contents
- Mailbox database files
- Yahoo passwords/app passwords
- API keys or other secrets
