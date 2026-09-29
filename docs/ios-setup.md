# iPhone Safari and Home Screen setup

Safari’s Photos/Files picker is the primary capture path. It supports selecting multiple screenshots without a
native app or Shortcut; RouteList receives only images you select and has no background camera-roll access. A
Home Screen browser bookmark is optional. Consider a native share extension only if a measured user need
warrants it.

**Status: deployed and browser-verified (September 27, 2026).** Release
`20260927-heic` accepted two raw HEIC uploads on the public site in 69.9 seconds,
with the saved session unchanged. Both original iPhone HEIC files passed the
complete container OCR path with all eight expected address rows. Fresh TAP
consent loaded three vehicles. Physical iPhone hardware and vehicle-command
acceptance remain untested.

**File formats:** PNG, JPEG, HEIC, and HEIF are accepted. iPhone HEIC images
are converted automatically on the server before the three OCR engines run;
no Shortcut, export, or manual conversion is needed. Conversion stays local,
respects the 12 MiB / 20 megapixel limits, applies orientation, and removes
metadata from the temporary OCR copy.

## Sign in and build a route

1. In Safari, open TAP, tap **Menu → Other Tools**, then **Open TeslaRouter**. You can also go
   directly to [TeslaRouter](https://teslarouter.javadevjt.tech).
2. Tap **Continue with TAP**.
3. Sign in with TAP and approve RouteList access on the TAP page.
4. In **Import your stops**, choose an authorized **Vehicle**. Set **Default state** if
   a screenshot omits its state.
5. Tap **Choose route screenshots**. Choose **Photo Library** and select multiple screenshots. Safari's native
   Photos picker is supported. If the screenshots are in Files, use the file picker instead.
6. Reorder the selected screenshots with the move controls, then tap **Read addresses**. The app extracts
   addresses with three local engines: Tesseract, PaddleOCR, and EasyOCR. No OpenAI key or hosted OCR is used.
   Keep the page open while processing; each screenshot runs through all three engines.
7. In **Review & send**, correct extracted text and remove unwanted candidates before
   sending. Rows show engine agreement. If a row requires review, compare the literal readings with the original
   screenshot, then confirm or edit it; sending stays blocked until it is reviewed. The page cannot add an address
   from scratch and has no manual text importer. Include the full street, apartment, and city in each screenshot;
   overlap scrolling screenshots so a clipped row is also captured in full.
8. Send a route group yourself with **Send Route 1 to Tesla** (the route number changes for each group). Each
   route respects Tesla's eight-waypoint limit. For automatic navigation, select **Use Automatic Navigation**,
   then tap **Start Automatic Navigation**.

To return to TAP, open TeslaRouter's **Menu** and choose **TAP Dashboard** or **Other Tools**.

TAP grants last up to eight hours and are not refreshed automatically; browser sessions expire after four
hours of inactivity. Sign in again after expiry or an application restart. Revoked TAP access blocks browser
and navigation requests.

## Add Safari to the Home Screen

Safari is the primary flow. For an iOS 26+ Home Screen shortcut that opens in the browser, while the RouteList
page is open in Safari, open the Page Menu or Share menu and tap **Add to Home Screen**. Leave **Open as Web
App** off; WebKit documents that this saves a browser bookmark. Standalone mode remains optional and unverified
on a physical iPhone. See WebKit’s
[Safari 26 release notes](https://webkit.org/blog/17333/webkit-features-in-safari-26-0/) and Apple’s
[iPhone guide to bookmarking a website](https://support.apple.com/guide/iphone/bookmark-a-website-iph42ab2f3a7/ios)
for setup.

With **Open as Web App** off, the Home Screen icon is a browser bookmark, not a native app. Standalone web-app
mode remains optional and unverified on a physical iPhone. Safari’s native Photo Library picker can select
multiple screenshots; Shortcuts, share-sheet import, and a native app remain proposed work.
