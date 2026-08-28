# PDF Werkstatt – Android

Eine kleine Android-PDF-App in Kotlin + Jetpack Compose.

## Funktionen

- Rückgängig für die letzten PDF-Bearbeitungsschritte (bis zu 20 Stände)

- PDF öffnen und anzeigen
- Zwischen Seiten blättern
- Text auf einer PDF-Seite platzieren
- Handschriftliche Unterschrift mit Finger/Stift zeichnen und platzieren
- Weitere PDFs an die aktuelle PDF anhängen
- Aktuelle Seite als eigene PDF exportieren
- Bearbeitete PDF über Androids Dateiauswahl unter neuem Namen speichern

## Technischer Aufbau

- UI: Jetpack Compose
- Anzeige: Android `PdfRenderer`
- PDF-Bearbeitung: `com.tom-roush:pdfbox-android:2.0.27.0`
- Dateizugriff: Android Storage Access Framework (`OpenDocument`, `CreateDocument`)

## Starten

1. Projektordner in Android Studio öffnen.
2. Falls Android Studio nach SDK-Komponenten fragt: Android SDK 37 installieren.
3. Gradle-Sync ausführen.
4. Android-Gerät per USB verbinden oder Emulator starten.
5. `Run` drücken.

## Hinweis zur Unterschrift

Die aktuelle Version fügt eine **sichtbare elektronische Unterschrift** als Grafik in die PDF ein.

Das ist nicht dasselbe wie eine **kryptografische PDF-Signatur mit Zertifikat (PKCS#12/PAdES)**. Dafür müsste zusätzlich ein Zertifikats-/Keystore-Workflow eingebaut werden.

## Aktuelle MVP-Grenzen

- Vorhandener PDF-Text wird nicht wie in Word direkt umgeschrieben.
- Text wird als neuer Inhalt auf die PDF gelegt.
- „Seite trennen“ exportiert derzeit die aktuell sichtbare Seite als eigene PDF.
- Seitenreihenfolge per Drag & Drop und mehrere Textgrößen sind gute nächste Ausbaustufen.


## APK automatisch bauen

Im Projekt liegt `.github/workflows/build-apk.yml`.

Wenn das Projekt in ein GitHub-Repository hochgeladen wird, baut GitHub Actions automatisch:

`app/build/outputs/apk/debug/app-debug.apk`

Die APK wird anschließend als Build-Artefakt `PDF-Werkstatt-APK` bereitgestellt.
