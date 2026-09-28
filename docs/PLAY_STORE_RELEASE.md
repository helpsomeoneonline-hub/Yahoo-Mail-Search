# Google Play release plan

## Product model
Recommended model: free tier + optional Pro subscription.

### Free
- Connect one Yahoo account
- Manual encrypted archive sync
- Basic search
- Bulk cleanup with confirmation
- One alert rule
- Light / Dark / Auto theme

### Pro
- Unlimited alert rules
- Background email monitoring
- Background archive automation
- Advanced search and automation features as they are added
- Premium recovery and archive-health tools

Google Play subscription product ID used by the app:
`mail_search_pro`

The debug APK is intentionally Pro-unlocked so the full feature set can be tested before Play Console products are configured.

## Play Console setup still required before sale
1. Create the app in Google Play Console.
2. Configure the subscription product `mail_search_pro` and at least one base plan.
3. Add a public privacy-policy HTTPS URL.
4. Complete Data Safety accurately for email content, credentials, user-provided GitHub storage, and diagnostics if any are later added.
5. Create a signed release App Bundle (.aab) with Play App Signing.
6. Add store listing text, screenshots, icon, feature graphic, support email, and content rating.
7. Test purchases using Play license testers / internal testing.

## Target SDK
This project is configured for Android 16 / API 36 for 2026 Google Play submission requirements.

## Branding note
Before public release, review the product name and store listing so they do not imply affiliation with Yahoo. A neutral brand name may be preferable for a commercial listing while stating compatibility with Yahoo Mail in descriptive text.
