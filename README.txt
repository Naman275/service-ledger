SERVICE LEDGER - ANDROID PILOT
=============================

An offline-first service book for independent repair technicians.
This is a free pilot, not a finished Google Play production release.

IMPLEMENTED
-----------
- Add, edit, search and delete customers.
- Import one selected phone contact without full address-book permission.
- Add, edit and delete appliances under each customer.
- Record, edit and delete service visits, work, replaced parts, INR charges,
  amounts received so far, remaining balances and the next service date.
- Choose dates from a calendar, with a clear-date action for optional dates.
- Attach one optional photo to an appliance or service visit using the system
  photo picker; replace/remove it and preview it on the detail screen.
- View overdue and due-today appliances; manually share a reminder or visit
  summary through Android's share sheet. No customer messages are automatic.
- Optional daily local notifications using WorkManager. Android can delay
  delivery; this is not an exact-time alarm.
- Encrypted manual backup and transactional replacement restore.
- No account, backend, ads, analytics, payment collection or cloud sync.

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
  .\tools\build.ps1 :app:bundleRelease

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

Release signing is intentionally NOT configured. Do not upload the debug APK
or treat an unsigned release bundle as production-ready.

DATA AND FOLLOW-UP RULES
-----------------------
Android 8.0 (API 26) or newer is required; target SDK is 36.
Room/SQLite stores customer -> appliance -> service-visit relationships in
app-private storage. It is not an independently encrypted database. Android's
device encryption and a strong screen lock are important.
No destructive migration fallback is enabled. Room schema JSON is exported
under app\schemas and should be kept in version control for future migrations.
Version 0.2.0 uses schema 2. Install it as an update, without uninstalling.
The 1 -> 2 migration preserves existing customer/appliance/service data and
maps an old paid flag to the full received amount, or zero when unpaid.

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
Received amounts are cumulative totals, not individual payment transactions.
For a charge of INR 100 and INR 50 received, pending is INR 50. To record another
INR 20, change received-so-far to INR 70. Overpayments and negative values are
rejected. Mark fully paid copies the total charge into the received field.

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

Format: .slbackup, SLBACK02 header, random 16-byte salt, random 12-byte IV,
AES-256-GCM with a 128-bit authentication tag, and PBKDF2-HMAC-SHA256 with
210,000 iterations. A new salt and IV are used for each export.
Passwords require at least 12 characters and are not saved to preferences,
database or saved instance state. Forgot the password = cannot restore.

Backups include all customers, appliances, services, received amounts and
referenced photo bytes, not app settings. Photos are deduplicated and encoded
in the encrypted payload; a missing/damaged photo causes an explicit error.
SLBACK01/schema-1 backups from version 0.1.0 remain importable. The old app
cannot read schema-2 backups; update before restoring a new backup.
The complete encrypted input is bounded to 8 MB; the ledger supports at most
20,000 total records. The 8 MB cap includes encoded photos, so adding photos
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
Instrumentation tests cover the customer -> appliance -> service -> due-list
UI flow and required-field validation.

BEFORE A GOOGLE PLAY RELEASE
---------------------------
1. Choose a final app name and permanent applicationId. The current placeholder
   is dev.serviceledger.app; debug installs use dev.serviceledger.app.debug.
2. Test on your physical phone and pilot technicians' devices, including:
   - restart, rotate, background/resume and update without losing records;
   - valid backup to an off-device provider and restore on another installation;
   - incorrect password, corrupt backup, denied storage and canceled picker;
   - reminder permission denied/revoked and battery restrictions;
   - contact/photo picker selection and cancellation on the actual phone;
   - photo backup/restore, partial-payment edits and calendar date selection;
   - large text / accessibility and long customer records.
3. Add an owner-approved privacy policy at a public URL, support contact,
   store screenshots, final launcher artwork and accurate Data Safety/content
   rating/target-audience declarations. See PRIVACY-DRAFT.txt as a starting
   point; it is not a published policy or legal advice.
4. Configure your own release signing/upload key securely outside source
   control. Do not send passwords or private keys in chat.
5. Build a signed release AAB and use Play Console internal testing first.
6. Recheck current Play target-SDK, testing and policy requirements at submission.
7. Test any future database upgrade with a migration; never reset the database
   to resolve a migration error.

Current automated validation results are recorded in VALIDATION.txt.
