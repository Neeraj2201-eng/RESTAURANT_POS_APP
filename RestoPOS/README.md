# Resto POS – Restaurant billing app with Bluetooth printing

Android app (Kotlin + Jetpack Compose) for a restaurant:

- Table grid (free / occupied, with running total)
- Add items to a table, change quantity, remove items
- Menu manager: add, edit, delete items and change prices any time
- Tax / GST %, bill numbers, restaurant name / address / phone / footer on the bill
- Prints the bill on a **2 inch (58 mm) Bluetooth thermal printer** (ESC/POS). 3 inch also supported
- **Reports tab:** every settled bill is recorded; see daily sales for the whole month, month total, tap a day for its bills, download/share as CSV (opens in Excel / Google Sheets), print a day summary slip
- All data is saved on the phone, works fully offline

## Get the APK (no coding tools needed)

1. Create a free account at https://github.com and make a **new repository** (private is fine).
2. Upload everything in this folder to it (on a computer: "Add file" > "Upload files", drag the
   unzipped folder contents, including the hidden `.github` folder).
3. Open the **Actions** tab > **Build APK**. It starts automatically after the upload
   (or press **Run workflow**). Wait about 5 minutes for the green tick.
4. Open the finished run, scroll to **Artifacts**, download **RestoPOS-apk** (a zip), unzip it
   to get `app-debug.apk`.
5. Copy the APK to your Android phone and tap it to install. Allow
   "Install unknown apps" for your file manager or browser when asked.

### Alternative: Android Studio
Open this folder in Android Studio (Koala or newer), let it sync, then
**Build > Build Bundle(s) / APK(s) > Build APK(s)**. The file is in
`app/build/outputs/apk/debug/app-debug.apk`.


## Using 3 devices (sync)

All devices share open tables, the menu, shared bill details, bill numbers and sales through a
free Firebase Realtime Database.

1. Go to https://console.firebase.google.com, sign in, **Add project** (turn Analytics off).
2. Left menu: **Build > Realtime Database > Create Database**, pick a location, **Start in test mode**.
3. Open the **Rules** tab, replace the text with the following and tap **Publish**
   (this stops test mode from expiring after 30 days):
   `{ "rules": { ".read": true, ".write": true } }`
4. On the **Data** tab copy the database URL shown at the top (looks like
   `https://yourproject-default-rtdb.firebaseio.com`).
5. On every device: **Settings > Multi-device sync**, paste the URL, tap **Save & connect**.
   The Tables screen shows "Synced" when connected.

Do this while no table has an open order. Anyone who knows the URL can read and write the data,
so keep it private. Without internet the app keeps working and syncs again when back online.

## Areas, payments and reports

- Tables screen has an **area dropdown**: Dining area (T1-T8), Ground Floor (R-101 to R-107),
  First Floor (R-201 to R-219).
- **Settle & clear** asks for payment mode (Cash, UPI, Room, Pending), the amount paid (editable),
  and the customer name when anything is pending.
- **Reports**: business day is 2:00 AM to 2:00 AM (a sale at 1 AM counts for the previous day).
  Use Prev / Next to see earlier months and tap any day for its bills.

## First-time setup on the phone

1. Turn the printer on. In Android **Settings > Bluetooth**, pair it (PIN is usually `0000` or `1234`).
2. Open Resto POS > **Settings**: tap **Choose Bluetooth printer** and pick it.
   Keep **Paper width = 2 inch**. Tap **Print test page**; the line of digits should fit
   exactly on one line.
3. Fill in restaurant name, address, phone, tax %, number of tables.
4. **Menu** tab: edit the sample items and prices to your own.

## Daily use

1. **Tables** tab: tap a table.
2. **Add items** tab: tap an item once per quantity.
3. **Bill** tab: adjust quantities, then **Print bill**.
4. After the customer pays, tap **Settle & clear table**. This records the sale in **Reports**.
5. **Reports** tab: choose the month (Prev / Next), tap **Download month** (saved in your phone's Downloads folder) or tap a day for **Download / Share / Print** of that day.

Price changes in the Menu apply to items added afterwards; items already on an open table
keep the price they were added with.

## Troubleshooting

- *Print failed / nothing prints*: printer must be on, paired, and in range; close any other
  app that is connected to it (only one device can connect at a time).
- *Garbled text*: the app prints plain English text only (use "Rs" for rupees).
- *Text wraps or is cut*: change **Paper width** in Settings (2 inch = 32 characters).
- Bluetooth permission prompt appears the first time you print or choose a printer; allow it.

## Project layout

- `app/src/main/java/com/restopos/app/MainActivity.kt` – all screens
- `app/src/main/java/com/restopos/app/Data.kt` – menu, orders, settings storage
- `app/src/main/java/com/restopos/app/Printer.kt` – Bluetooth connection and ESC/POS bill layout
- `app/src/main/java/com/restopos/app/Reports.kt` – daily/monthly sales report and CSV export
- `.github/workflows/build-apk.yml` – cloud APK build
