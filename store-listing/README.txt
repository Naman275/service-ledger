SERVICE LEDGER - GOOGLE PLAY LISTING ASSETS
=========================================
English (United Kingdom), app version 0.3.1.

Uploaded and saved as a Play Console draft on 13 October 2026. The owner
chose to complete the AI-asset declaration manually. No selection was made
on that declaration, and the listing was not submitted for review.
The icon, banner and description text were created with AI assistance;
the phone screenshots are captures of the actual app, not generated mockups.

icon-512.png: 512 x 512, opaque PNG. Higher-resolution rendering of the
installed app's teal/white ledger mark. Google applies the outer icon mask.
feature-graphic.png: 1024 x 500, opaque 24-bit PNG, original artwork.
phone/: six 1512 x 2688 (9:16) opaque PNG screenshots, in upload order.

Screenshots are native Android/Compose renders using Robolectric API 35 at
504 x 896 dp / xxhdpi, not mocked-up interfaces or physical-device captures.
They depict the real app UI with fictional customer/service/payment records.
There are no real phone numbers, addresses, customer photos or transactions.
The installed app and release bundle contain none of this demonstration data.
The screenshots are not stretched, composited or decorated with claims.

REGENERATE ON WINDOWS
---------------------
From the repository root:

  $env:LEDGER_SCREENSHOTS = "$env:LOCALAPPDATA\ServiceLedger\store-captures"
  .\tools\build.ps1 :app:testDebugUnitTest --tests 'dev.serviceledger.LedgerWorkflowTest.store listing*'
  .\tools\store-assets.ps1

The generator uses Windows System.Drawing and Segoe UI, with no downloaded
fonts or third-party artwork. It keeps screenshots at their native dimensions
and removes the unused PNG alpha channel for Play's image requirements.
Dates in the demonstration follow the test run's current date.

Listing copy is in listing-en-GB.txt. Review claims and regenerate screenshots
when the app changes. Upload phone captures only as phone screenshots; no
tablet, Chromebook, TV or XR screenshots are represented by this asset set.

Requirements:
https://support.google.com/googleplay/android-developer/answer/9866151
