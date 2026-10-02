# Datenschutzerklärung — SMS Tech

_Zuletzt aktualisiert: 2. Oktober 2026_ · 🇬🇧 [English](PRIVACY.md) · 🇫🇷 [Français](PRIVACY.fr.md) · 🇮🇹 [Italiano](PRIVACY.it.md) · 🇪🇸 [Español](PRIVACY.es.md)

> **Übersetzung.** Diese deutsche Fassung ist eine Übersetzung, die zur Information bereitgestellt
> wird. Verbindlich sind die [englische](PRIVACY.md) und die [französische](PRIVACY.fr.md) Fassung
> dieser Erklärung; bei Abweichungen gehen diese beiden Fassungen vor.

SMS Tech (`com.filestech.sms`) gehört zur Suite **Files Tech**, herausgegeben von **Patrice
Haltaya**. Siehe auch die [Nutzungsbedingungen](TERMS.de.md).

## Was wir erheben

**Nichts.** SMS Tech erhebt, übermittelt und aggregiert keinerlei personenbezogene Daten.

Kein Analyse-SDK, kein Absturzmelder, kein Telemetrie-Endpunkt, keine Werbe-ID, keine
Fingerprinting-Bibliothek. Die Binärdatei enthält keinen Tracking-Code von Dritten.

## Was auf Ihrem Gerät bleibt

- Ihre SMS und MMS, in einer verschlüsselten Room-Datenbank (SQLCipher), geschützt durch einen
  Schlüssel, der vom AndroidKeyStore umhüllt wird.
- Die Metadaten der Unterhaltungen (Entwürfe, Anheften, Archivieren, Tresor, Präferenzen pro
  Unterhaltung).
- Die Einstellungen, in Android DataStore Preferences.
- Ein gesalzener PBKDF2-HMAC-SHA512-Hash Ihrer PIN (die PIN wird nie im Klartext gespeichert).
- Etwaige MMS-Anhänge, in `<files>/mms_attachments/`.
- Etwaige lokal erzeugte PDF-Dateien von Unterhaltungen, in `<files>/exports/`.
- Sofern Sie sie einrichten, Ihre Notfallkontakte sowie die Einstellungen des Notfallmodus und von
  Safety call.

Wie jede Standard-SMS-App schreibt SMS Tech Ihre Nachrichten außerdem in den Nachrichtenspeicher des
Android-Systems, der sie unabhängig von der App aufbewahrt.

Die Android-Systemsicherung ist **deaktiviert**: Ohne Ihre ausdrückliche Zustimmung werden diese
Daten weder auf Google Drive übertragen noch bei einer Geräteübertragung mitgenommen.

## Netzwerk

SMS Tech sendet keine Netzwerkanfragen. Seit Version 1.28.13 besitzt die App nicht einmal mehr die
Berechtigung `INTERNET`: Der Prozess der App kann keine Netzwerkverbindung öffnen. MMS werden über
den MMS-Dienst von Android übertragen, der das MMSC Ihres Mobilfunkanbieters kontaktiert, wenn Sie
eine MMS senden oder empfangen. Keine Update-Prüfung, keine Fernkonfiguration, kein Analyse-Ping.

Die Nachrichten, die Sie senden, laufen wie bei jeder SMS-App über das Netz Ihres
Mobilfunkanbieters. Der Entwickler erhält sie nie.

## Notfallfunktionen (inaktiv, solange Sie sie nicht aktivieren)

- **Notfallmodus.** Wenn Sie die Notruf-Schaltfläche drei Sekunden lang gedrückt halten, sendet SMS
  Tech eine SMS an die Notfallkontakte, die **Sie** ausgewählt haben. Wenn Sie die
  Standortberechtigung erteilt haben, genau oder ungefähr, und „GPS in die Notfall-SMS aufnehmen“
  eingeschaltet gelassen haben (in diesem Modus standardmäßig aktiviert), fragt die App Android in
  diesem Moment nach **einem** Standort: Sie übernimmt einen Standort, der weniger als fünf Minuten
  alt ist, sofern Android einen hat; andernfalls wartet sie höchstens acht Sekunden auf eine neue
  Standortbestimmung, und gelingt das nicht, übernimmt sie den letzten bekannten Standort, sofern er
  weniger als dreißig Minuten alt ist. Keine Ortung im Hintergrund und keine fortlaufende Ortung. Der
  Standort wird der SMS als Link `https://maps.google.com/?q=…` hinzugefügt; ist er nur ungefähr,
  verschiebt Android ihn um etwa zwei Kilometer, und die SMS weist darauf mit „(+/-N km)“ nach dem
  Link hin. Öffnet ein Kontakt diesen Link, ist es sein Browser, der Google Maps kontaktiert; die App
  selbst sendet nichts an Google. Wird der Standort verweigert oder ist er nicht verfügbar, weist die
  SMS darauf hin und wird ohne Koordinaten gesendet.
- **Safety call.** Wenn Sie die App nicht vor Ablauf der von Ihnen festgelegten Frist verwenden,
  sendet SMS Tech eine Kontroll-SMS an dieselben Kontakte. Sie enthält keinen Standort.
- **Notruf.** Die Anrufkacheln der Notrufseite bieten die Notrufnummern des Landes an, in dem Ihr
  Telefon im Netz registriert ist (die 112 ist immer dabei). Wenn Sie eine davon antippen, fragt die
  App nach der Anrufberechtigung (`CALL_PHONE`) und führt den Anruf, wenn Sie sie erteilen, selbst
  aus; wenn Sie sie verweigern, öffnet sie die Telefon-App mit bereits eingetragener Nummer, wofür
  keine Berechtigung nötig ist. Die Verknüpfung auf dem Sperrbildschirm öffnet immer die Telefon-App.
- Keine dieser Funktionen läuft im Tarnmodus, der durch die Tarn-PIN geöffnet wird.
- Diese Nachrichten sind gewöhnliche SMS: Ihr Mobilfunkanbieter kann sie gemäß Ihrem Tarif
  berechnen.

## Berechtigungen

Die Begründung jeder Berechtigung finden Sie in [PERMISSIONS.md](PERMISSIONS.md).

## Ihre Rechte

Da keine personenbezogenen Daten Ihr Gerät verlassen, gibt es bei keinem entfernten System etwas
einzusehen, zu berichtigen oder zu löschen. So löschen Sie Ihre Daten in SMS Tech: Deinstallieren
Sie die App (Android löscht ihre Daten automatisch) oder verwenden Sie **Einstellungen → Alle meine
Daten löschen**; dies löscht die verschlüsselte Datenbank, die Keystore-Aliase, die
zwischengespeicherten Anhänge und die exportierten PDF-Dateien. Die im Nachrichtenspeicher des
Android-Systems aufbewahrten Nachrichten verwalten Sie über Android oder über jede SMS-App.

## Kontakt

Bei allen Fragen: **contact@files-tech.com**.
