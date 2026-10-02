# Informativa sulla privacy — SMS Tech

_Ultimo aggiornamento: 2 ottobre 2026_ · 🇬🇧 [English](PRIVACY.md) · 🇫🇷 [Français](PRIVACY.fr.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇪🇸 [Español](PRIVACY.es.md)

> **Traduzione.** Questa traduzione italiana è fornita a titolo informativo. Fanno fede le versioni
> [inglese](PRIVACY.md) e [francese](PRIVACY.fr.md) di questa informativa; in caso di discrepanza,
> prevalgono le versioni inglese e francese.

SMS Tech (`com.filestech.sms`) fa parte della suite **Files Tech**, pubblicata da **Patrice
Haltaya**. Si vedano anche le [condizioni d'uso](TERMS.it.md).

## Che cosa raccogliamo

**Nulla.** SMS Tech non raccoglie, non trasmette né aggrega alcun dato personale.

Nessun SDK di analisi, nessun sistema di segnalazione degli arresti anomali, nessun endpoint di
telemetria, nessun identificativo pubblicitario, nessuna libreria di fingerprinting. Il binario non
contiene alcun codice di tracciamento di terze parti.

## Che cosa resta sul suo dispositivo

- I suoi SMS e MMS, in un database Room cifrato (SQLCipher) protetto da una chiave incapsulata
  dall'AndroidKeyStore.
- I metadati delle conversazioni (bozze, fissaggio in alto, archiviazione, cassaforte, preferenze per
  conversazione).
- Le impostazioni, in Android DataStore Preferences.
- Un hash con sale PBKDF2-HMAC-SHA512 del suo codice PIN (il PIN non viene mai memorizzato in
  chiaro).
- Gli eventuali allegati MMS, in `<files>/mms_attachments/`.
- Gli eventuali PDF delle conversazioni generati localmente, in `<files>/exports/`.
- Se li configura, i suoi contatti di emergenza e le impostazioni della modalità emergenza e del
  Safety call.

Come ogni app SMS predefinita, SMS Tech scrive anche i suoi messaggi nell'archivio messaggi di
sistema di Android, che li conserva indipendentemente dall'applicazione.

Il backup di sistema di Android è **disattivato**: questi dati non finiscono né su Google Drive né
in un trasferimento di dispositivo senza il suo consenso esplicito.

## Rete

SMS Tech non effettua alcuna richiesta di rete. Dalla versione 1.28.13 non detiene nemmeno più
l'autorizzazione `INTERNET`: il suo processo non può aprire alcuna connessione di rete. Gli MMS sono
instradati dal servizio MMS di Android, che contatta l'MMSC del suo operatore quando lei ne invia o
ne riceve uno. Nessun controllo degli aggiornamenti, nessuna configurazione remota, nessun ping di
analisi.

I messaggi che lei invia passano attraverso la rete del suo operatore, come con qualsiasi app SMS.
Lo sviluppatore non li riceve mai.

## Funzioni di emergenza (inattive finché lei non le attiva)

- **Modalità emergenza.** Quando tiene premuto il pulsante di emergenza per tre secondi, SMS Tech
  invia un SMS ai contatti di emergenza che **lei** ha scelto. Se ha concesso l'autorizzazione alla
  posizione, esatta o approssimativa, e ha lasciato «includi la mia posizione» (attivo per
  impostazione predefinita in questa modalità), l'applicazione chiede ad Android **una** posizione in
  quell'istante: riprende una posizione risalente a meno di cinque minuti se Android ne dispone,
  altrimenti attende un nuovo rilevamento per al massimo otto secondi e, in mancanza, riprende
  l'ultima posizione nota se risale a meno di trenta minuti. Nessun tracciamento in background né
  continuo. La posizione viene aggiunta all'SMS sotto forma di link `https://maps.google.com/?q=…`;
  quando è solo approssimativa, Android la sposta di circa due chilometri e l'SMS lo indica con
  «(+/-N km)» dopo il link. Se un contatto apre questo link, è il suo browser a contattare Google
  Maps; l'applicazione, invece, non invia nulla a Google. Se la posizione è rifiutata o non
  disponibile, l'SMS lo dice e parte senza coordinate.
- **Safety call.** Se lei non usa l'applicazione entro il termine che ha fissato, SMS Tech invia un
  SMS di verifica agli stessi contatti. Non contiene alcuna posizione.
- **Chiamata di emergenza.** I riquadri di chiamata della schermata Emergenza propongono i numeri di
  emergenza del paese in cui il suo telefono è registrato sulla rete (il 112 è sempre presente).
  Quando ne tocca uno, l'applicazione chiede l'autorizzazione alle chiamate (`CALL_PHONE`) e, se lei
  la concede, effettua la chiamata direttamente; se la rifiuta, apre il tastierino telefonico con il
  numero già inserito, che non richiede alcuna autorizzazione. La scorciatoia sulla schermata di
  blocco apre sempre il tastierino telefonico.
- Nessuna di queste funzioni viene eseguita nella sessione esca aperta dal codice di panico.
- Questi messaggi sono normali SMS: il suo operatore può addebitarli in base al suo piano tariffario.

## Autorizzazioni

Si veda [PERMISSIONS.md](PERMISSIONS.md) per la motivazione di ciascuna autorizzazione.

## I suoi diritti

Poiché nessun dato personale lascia il suo dispositivo, non c'è nulla da consultare, rettificare o
cancellare presso un sistema remoto. Per cancellare i suoi dati in SMS Tech: disinstalli
l'applicazione (Android ne elimina automaticamente i dati) oppure usi **Impostazioni → Elimina
tutti i miei dati**, che cancella il database cifrato, gli alias del Keystore, gli allegati in
cache e i PDF esportati. I messaggi conservati dall'archivio messaggi di sistema di Android si
gestiscono da Android o da qualsiasi app SMS.

## Contatto

Per qualsiasi domanda: **contact@files-tech.com**.
