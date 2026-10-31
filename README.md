# FieldGuide GPS – Android (Cloud-Build vorbereitet)

Native Offline-GPS-Spurführung für **Samsung Galaxy A55** mit GPS, Feldverwaltung,
A-B-Leitlinien, parallelen Spuren, Fahrweg, Gerätedaten und lokaler Speicherung.
Die erste Version ist nur zur **manuellen visuellen Spurführung** ausgelegt.

## Android-only

Die App benötigt beim Benutzen **weder Windows noch Android Studio**.
Damit eine installierbare APK entsteht, ist eine einmalige Erstellung über
GitHub Actions vorgesehen. Die komplette Kurzanleitung steht in
[ANLEITUNG_ANDROID.txt](ANLEITUNG_ANDROID.txt). Ein Cloud-Build wurde hier
nicht ausgeführt und es liegt **noch keine APK** bei.

Nach dem Hochladen des Projekts in ein eigenes *privates* GitHub-Repository
startet ein GitHub-Actions-Workflow automatisch und veröffentlicht eine
Test-APK als `FieldGuideGPS.apk` unter **Releases**. Der Workflow baut mit
Java 17, Android SDK 35, Gradle 8.13 und den im Projekt definierten
Android/Kotlin-Plugins.

## Was implementiert ist

- Positionsdaten vom Geräte-GNSS (Android `LocationManager`), Qualitätskontrolle
- Felder anlegen, Grenze mit GNSS aufzeichnen oder schematisch Punkte setzen
- A-B-Leitlinie, parallele Sollspuren, Arbeitsbreite und Überlappung
- Seitliche Abweichung und Warnung bei schlechter GNSS-Genauigkeit
- 2D-Karte und 3D-artige perspektivische Spurführungsanzeige
- Geräte-/Antennenversatz, Arbeits- und Streckenaufzeichnung
- Flächenfortschritt als Raster-Schätzung, Export nach GPX, GeoJSON, CSV
- Lokale JSON-Speicherung; keine Internetberechtigung der App

## Was fehlt

Android-Build, A55-Gerätetest und Kontrolle mit echtem GNSS. Kein RTK-Backend,
keine NTRIP-Anbindung, keine automatische Lenkung, keine vermessungstaugliche
Flächenmessung. Die als APK ausgelieferte Debug-Version benötigt später für
nahtlose Updates eine dauerhaft sichere Signatur.

Siehe `QA_STATUS.md` für den dokumentierten Prüfstand. Die ursprüngliche
Nutzer-Bildvorlage liegt in `REFERENZ_Spurfuehrung.jpg`.
