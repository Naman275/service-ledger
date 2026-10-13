SERVICE LEDGER - ANDROID PILOT
=============================

An offline-first customer, service and payment ledger for small service businesses.
This is a free pilot, not a finished Google Play production release.

IMPLEMENTED
-----------
- Add, edit, search and delete customers.
- Import one selected phone contact without full address-book permission.
- Add, edit and delete appliances under each customer.
- A friendly dashboard with total charged, received and outstanding balances,
  recent services and quick actions. Follows the device's light/dark theme.
- Record services directly against a customer, with an optional appliance.
- Record individual payments against a selected service, with a date, method,
  reference and notes. Customer and overall balances update automatically.
- Search pending payments, filter overdue services, and browse payment history.
- Correct an incorrect payment by voiding it with a reason; keep its history.
- Preview a payment reminder and open WhatsApp or WhatsApp Business addressed
  to an explicitly confirmed international number. The user still presses Send.
- Separate payment due dates from next-service follow-up dates.
- Choose dates from a calendar, with a clear-date action for optional dates.
- Attach one optional photo to an appliance or service visit using the system
  photo picker; replace/remove it and preview it on the detail screen.
- View overdue and due-today appliances; manually share a reminder or visit
  summary through Android's share sheet. No customer messages are automatic.
- Optional daily local notifications using WorkManager. Android can delay
  delivery; this is not an exact-time alarm.
- Encrypted manual backup and transactional replacement restore.
- No account, backend, ads, analytics, online payment processing or cloud sync.

CARD DESIGN (0.3.1)
-------------------
Cards now use consistent roles rather than one identical surface everywhere:
- Service records and service follow-ups: soft blue, with a tool icon and label.
- Payment summaries / balances: warm sand, with a wallet icon and label.
- Received payments: soft mint, with a check icon and label.
- Customers, settings and voided payments: neutral surfaces; voided entries are
  explicitly labeled, not presented as successful receipts.
Light and dark palettes use paired foreground/background colors. Automated tests
check at least 4.5:1 contrast for card content, headings and shared action/secondary
text. Icons, labels and written statuses complement color; color is never the only
record-type or payment-status cue. Red is reserved for overdue balances, errors
and destructive actions,
not every pending payment. Service details and actions are grouped separately from
payment actions; amounts in service lists are explicitly labeled "To collect".

Design references (the palette itself is an app-specific choice):
https://developer.android.com/develop/ui/compose/designsystems/material3
https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html

PAYMENT WORKFLOW
----------------
1. Open Customers, add a customer, and open their ledger.
2. Tap Record service. Enter work and charge; optionally select an appliance,
   payment due date, next-service date and photo.
3. Open the saved service and tap Record payment. Enter the amount received,
   date and method (Cash, UPI, Bank transfer, Card or Other).
4. Record later installments as additional payments, not edits to a total.
5. Use Payments -> Pending to find unpaid services. Overdue means a payment
   due date before today and an outstanding balance; no due date is not overdue.
6. Open a service -> Remind on WhatsApp. Include a country code, for example
   +91 98765 43210, check the message, then press Send in WhatsApp.
   Opening WhatsApp is not proof of sending or delivery. No messages are automatic.
7. Use Void payment only to correct a mistaken entry. A reason is required;
   the entry remains visible and its amount no longer counts as received.
   Voiding is not a refund and does not move money.

BUILD
-----
Requires JDK 17, Android platform 36 and Android build-tools 35.0.0.
Gradle Wrapper 8.13 is included with distribution checksum verification.
Dependencies download from Google Maven, Maven Central and Gradle Plugin Portal.

On this workspace, the SDK is installed under .tools\android-sdk.
The emulator package, API 35 emulator image and test virtual device were
removed at the owner's request after a OneDrive/Purview certificate alert.
Compilation tools and the built app are preserved. To run device tests again,
connect a physical Android device or use an organization-approved emulator/SDK
outside OneDrive. Do not reinstall emulator tooling into this synced workspace.
From PowerShell:

  .\tools\build.ps1

That runs unit tests, Android lint and the debug APK build.
The helper places generated build files outside OneDrive under
%LOCALAPPDATA%\ServiceLedger\build, using the ledgerBuildRoot Gradle property.
This avoids the generated-directory locks observed in the synced folder.
For selected tasks:

  .\tools\build.ps1 :app:assembleDebug
  .\tools\build.ps1 :app:connectedDebugAndroidTest
  .\tools\release.ps1

For a different machine, install Android Studio / Android SDK and either set
ANDROID_HOME or configure local.properties with sdk.dir. The helper script uses
the project-local SDK if present, otherwise the normal Android SDK configuration.
Open the root folder as a Gradle project in Android Studio.
The .tools directory is disposable tooling, not application source.

OUTPUTS
-------
The following paths are relative to %LOCALAPPDATA%\ServiceLedger\build:
APK: app\outputs\apk\debug\app-debug.apk
Test APK: app\outputs\apk\androidTest\debug\app-debug-androidTest.apk
Unit tests: app\reports\tests\testDebugUnitTest\index.html
Lint: app\reports\lint-results-debug.html
Release AAB: app\outputs\bundle\release\app-release.aab
Older files under the source tree's app\build directory are NOT current outputs.

Release signing reads LEDGER_KEYSTORE_PATH, LEDGER_STORE_PASSWORD,
LEDGER_KEY_ALIAS and LEDGER_KEY_PASSWORD from the build process environment.
A release build fails explicitly if signing is missing. Do not upload the debug APK.

On Windows, create a new upload key ONCE, outside the repository:

  .\tools\create-upload-key.ps1
  .\tools\release.ps1

The default signing directory is %LOCALAPPDATA%\ServiceLedger\signing.
The helper generates a PKCS12 upload key and restricts directory access to the
current Windows user and SYSTEM. Its password file is protected with Windows
DPAPI, not stored in plaintext. release.ps1 decrypts it only for the build and
restores the signing environment afterward. Never log or commit signing secrets.
Back up the key AND its password in a secure password manager/offline vault
before publishing: the DPAPI file alone cannot be moved to another Windows
account/machine. Keep the public upload-certificate.der for certificate checks.
For another platform or a recovered key, supply the four environment variables
securely and run the build helper's :app:bundleRelease task.

The Google Play application ID is com.namansingla.serviceledger. Debug builds
retain dev.serviceledger.app.debug, so the Play app is a separate installation
from the pilot. Export an encrypted backup in the pilot and restore it in the
Play installation to transfer records. Restore replaces, rather than merges,
the destination ledger.

DATA AND FOLLOW-UP RULES
-----------------------
Android 8.0 (API 26) or newer is required; target SDK is 36.
Room/SQLite stores customer -> service -> payment relationships, with optional
customer-owned appliances linked to services, in
app-private storage. It is not an independently encrypted database. Android's
device encryption and a strong screen lock are important.
No destructive migration fallback is enabled. Room schema JSON is exported
under app\schemas and should be kept in version control for future migrations.
Version 0.3.1 uses schema 3 (unchanged from 0.3.0).
Install it as an update, without uninstalling.
The 1 -> 2 migration preserves existing customer/appliance/service data and
maps an old paid flag to the full received amount, or zero when unpaid.
The 2 -> 3 migration links each existing service to its customer and turns a
positive received total into one "Previous receipts" entry. Its payment date
and method are unknown; the app does not invent them. Zero receipts create no
payment. Both schema-1 and schema-2 upgrades are supported.
Services/customers with any payment history (even voided entries) cannot be
deleted. Deleting an appliance keeps its services and payments as general
services. Deleting a customer without payment history removes their services
and appliances after confirmation. Restore is the explicit replacement operation.

Each appliance has an optional initial due date. Once it has service visits,
the latest service DATE controls its next due date. Same-day visits use their
original recording timestamp, then ID as a deterministic tie breaker.
An empty next-due field on the latest service explicitly means no follow-up.
Editing an old service does not make it the newest service.
Deleting the newest visit reveals the previous visit's due date; deleting all
visits reveals the appliance's initial due date.
The due list includes today and earlier, based on the phone's local date.
Dates use a calendar picker and accept 1900 through 2200. Optional dates can
be cleared. Calendar values use UTC day boundaries to avoid timezone shifts.
Amounts are stored in integer paise, never floating-point rupees.
For general services, each service's own next-service date controls its follow-up;
clear that date when the follow-up is no longer needed.
Received amounts are derived from individual, non-voided payments. For a charge
of INR 100 and payments of INR 50 and INR 20, pending is INR 30. Zero/negative
payments and overpayments are rejected transactionally. Lowering a service charge
below its received total is rejected. A payment belongs to exactly one service.
Customer and total balances include all service charges and active receipts,
not just services with an appliance or a scheduled due date.

CONTACTS AND PHOTOS
-------------------
Contact import opens ACTION_PICK for a phone entry. It reads only that
selected entry's name and phone number, replaces those two draft form fields,
and leaves the address unchanged. Review before saving; no automatic deduping.
No READ_CONTACTS or broad media/storage permission is requested.

Photos are selected with Android's system photo picker (document-picker
fallback on older devices). Each record has at most one optional photo.
The app copies it into app-private files, so removing the original from the
gallery does not remove the attachment. Sources must be <= 20 MB. The app
applies EXIF orientation, resizes to <= 1280 pixels, and re-encodes a JPEG of
<= 256 KB without original EXIF/GPS metadata. No camera capture is implemented.
Filenames are content hashes; Room stores only photo references, not blobs.
Unreferenced files are removed after successful record changes or restore.
A photo selected in a canceled draft may remain privately until that cleanup.
Sharing a reminder/service summary sends text only, not attached images.

BACKUP SAFETY
-------------
Backups are explicitly selected through Android's document picker. There is
no automatic Google Drive integration. A cloud provider appears only if the
device has a compatible document provider installed and configured.

Format: .slbackup, SLBACK03 header, random 16-byte salt, random 12-byte IV,
AES-256-GCM with a 128-bit authentication tag, and PBKDF2-HMAC-SHA256 with
210,000 iterations. A new salt and IV are used for each export.
Passwords require at least 12 characters and are not saved to preferences,
database or saved instance state. Forgot the password = cannot restore.

Backups include all customers, appliances, services, payments, void reasons and
referenced photo bytes, not app settings. Photos are deduplicated and encoded
in the encrypted payload; a missing/damaged photo causes an explicit error.
SLBACK01/schema-1 and SLBACK02/schema-2 backups remain importable; previous
received totals become "Previous receipts" entries. Older apps cannot read
schema-3 backups; update before restoring a new backup.
The complete encrypted input is bounded to 8 MB; the ledger supports at most
40,000 total records, including payment history. The increase accommodates
migration of the previous 20,000-record limit without dropping receipts.
The 8 MB cap includes encoded photos, so adding photos
reduces the number of records that fit in a backup. At the limit, export fails
explicitly rather than silently omitting images. Remove unneeded attachments
before retrying. Limits are checked before import.
The importer authenticates, parses, type-checks and validates relationships
before replacing anything. Replacement runs inside one database transaction.
For restore, validated immutable photo files are installed before the database
transaction. A failed transaction leaves old photo references usable; unused
files are cleaned after a later successful record change/restore.
Duplicate IDs, orphan references, unsupported schemas, malformed numbers,
truncation and authentication failures are rejected.
Backup restore REPLACES rather than merges. There is a confirmation checkbox.

The last backup timestamp is recorded only after the destination is written
and closed. It does not prove that a cloud provider has finished syncing.
Canceling or failing an export can leave an empty/partial destination file;
do not mistake it for a successful backup. A failed export leaves any earlier
successful backup timestamp unchanged.
Restoring clears the last-backup timestamp, encouraging a fresh export.
The Backup screen recommends a backup when none exists or it is older than
7 days. This is an in-app reminder, not an automatic background backup.
Default Android cloud backup and device transfer are excluded by manifest
and extraction rules. Uninstalling loses local data unless a usable external
backup exists. Save a backup off this phone and test its restoration.

TESTS
-----
JVM tests cover exact money/date validation, due-date boundary and chronology
rules, backup roundtrips/tampering/passwords/limits/type checks, and Room
persistence, parent edits, cascades and all-or-nothing restore.
Room tests use Robolectric with a real SQLite-backed Room database.
Additional JVM tests cover v1 database migration on API 26/35, legacy encrypted
backup compatibility, contact queries, native photo normalization and Compose
calendar/payment widgets. These do not require installed emulator tooling.
Schema-2 migration tests cover partial receipts, photos and ownership on API
26/35. Ledger tests cover installments, corrections, protected deletion, duplicate
submission, overpayments, general services and invalid payment restores.
Native-rendered Robolectric UI tests cover the customer -> general service ->
payment -> reminder preview -> correction -> history workflow and draft discard.
Set LEDGER_SCREENSHOTS to a directory before running LedgerWorkflowTest to save
optional native-rendered previews. These are not physical-device screenshots.
Instrumentation tests cover the customer -> appliance -> service -> due-list
UI flow and required-field validation.

BEFORE A GOOGLE PLAY RELEASE
---------------------------
1. The chosen app name is Service Ledger; the permanent production applicationId
   is com.namansingla.serviceledger. Debug retains dev.serviceledger.app.debug.
2. Test on your physical phone and pilot technicians' devices, including:
   - restart, rotate, background/resume and update without losing records;
   - valid backup to an off-device provider and restore on another installation;
   - incorrect password, corrupt backup, denied storage and canceled picker;
   - reminder permission denied/revoked and battery restrictions;
   - contact/photo picker selection and cancellation on the actual phone;
   - photo backup/restore, partial-payment edits and calendar date selection;
   - large text / accessibility and long customer records.
3. The privacy policy source is docs/index.html, hosted through GitHub Pages
   from main's /docs folder at https://naman275.github.io/service-ledger/.
   It is linked in-app under More -> Read privacy policy. Owner: Naman Singla;
   support/privacy email: rudratheternity@gmail.com. Review the policy whenever
   data handling changes. Prepare store screenshots, final launcher artwork
   and accurate Data Safety/content rating/target-audience declarations.
4. Back up the local upload key and password securely outside source control.
   Do not send passwords or private keys in chat.
5. Build a signed release AAB with tools\release.ps1 and use Play Console internal
   testing first. Enable Play App Signing and distinguish Google's app signing
   key (installed apps) from this local upload key (bundle uploads).
6. Recheck current Play target-SDK, testing and policy requirements at submission.
7. Test any future database upgrade with a migration; never reset the database
   to resolve a migration error.

Current automated validation results are recorded in VALIDATION.txt.
