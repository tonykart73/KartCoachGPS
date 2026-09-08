# KartCoach GPS 0.3 — target pre-run + coach automatico per kart

MVP Android pensato per usare lo smartphone fissato al kart come datalogger GPS/sensori e coach visivo.

## Flusso previsto a Morcone

1. Apri l'app e autorizza il GPS.
2. **Prima di partire imposta il TARGET giro**, per esempio `31.500`. Sono accettati anche formati come `31,500` o `1:02.300`.
3. Finché il target non è impostato, l'avvio automatico della registrazione rimane bloccato.
4. La prima volta, se sei nel geofence di Contrada Canepino, l'app propone **Pista di Morcone** e chiede conferma.
5. Le volte successive, se la linea virtuale è già stata appresa, la pista viene riconosciuta automaticamente.
6. Quando inizi a muoverti in pista (> ~16 km/h con fix GPS accettabile), parte la registrazione automatica.
7. Se non esiste ancora una linea start/finish, il primo giro veloce chiuso viene usato per apprenderla automaticamente. Non devi fermarti a premere un pulsante sulla linea.
8. Dopo ogni giro l'app mostra immediatamente il delta rispetto al target, per esempio `+0.183 s` o `−0.052 s`.
9. Dal secondo giro valido l'app confronta i settori dei giri della sessione e calcola:
   - best lap;
   - giro ideale teorico (somma dei migliori micro-settori ottenuti nella stessa sessione);
   - potenziale recuperabile;
   - fino a 4 zone prioritarie.
10. Le regole del coach confrontano ingresso, minima, uscita, decelerazione e ripresa accelerazione e possono produrre indicazioni come:
   - FRENA / frenata più avanti e più corta;
   - INSERISCI / ingresso meno aggressivo;
   - RADDRIZZA / apri prima lo sterzo e fai scorrere;
   - GAS / riduci il tempo passato rallentato.
11. I punti generati diventano marker GPS del run successivo. In pista l'interfaccia mostra soltanto un colore pieno e un comando grande.
12. Se, dopo almeno un giro, il kart resta quasi fermo per circa 15 s, l'app interpreta il rientro ai box, salva il CSV e mantiene disponibile l'analisi. In alternativa premi **FINE SESSIONE**.

## Cosa può realmente sapere con il solo smartphone

GPS + variazione di velocità + direzione + accelerometro/giroscopio permettono di stimare dove inizi a rallentare, velocità di ingresso/minima/uscita, transizioni e rotazione del kart.

Con il solo smartphone NON conosciamo direttamente:
- pressione reale del freno;
- percentuale acceleratore;
- angolo reale del volante;
- RPM motore.

Perciò messaggi come “freni troppo presto” o “raddrizza prima” sono inferenze dinamiche, non letture di pedali/sterzo. Una futura integrazione AiM/MyChron o sensori esterni può rendere queste indicazioni molto più precise.

## Precisione GPS

La richiesta Android è impostata ad alta frequenza, ma la frequenza e precisione effettive dipendono dall'hardware del telefono. Per coaching metrico molto preciso, una versione successiva potrà supportare GNSS Bluetooth 10–25 Hz. Il marker usa anche la direzione di marcia per ridurre falsi trigger su tratti di pista geograficamente vicini.

## Morcone

Il profilo usa un geofence largo nella zona di Contrada Canepino soltanto per proporre il nome della pista. La geometria reale, la linea virtuale e i marker vengono appresi dalle coordinate registrate sul posto: non vengono inventati a partire da una mappa online.

La lunghezza nominale impostata per l'apprendimento iniziale è 800 m.

## File telemetria

CSV salvati in:

`Download/KartCoach/`

Colonne:

`time_ms,session_ms,lap,target_lap_ms,lat,lon,speed_kmh,bearing_deg,accuracy_m,ax,ay,az,gx,gy,gz`

## Aprire il progetto

Apri la cartella `KartCoachGPS` con Android Studio. Se Android Studio segnala che manca il wrapper eseguibile, usa il Gradle integrato/crea il wrapper dal menu di Android Studio e sincronizza il progetto.

Configurazione progetto:
- Kotlin
- Jetpack Compose
- minSdk 26
- target/compileSdk 37
- Java 17

## Test già eseguito

Il core Kotlin non Android (`Geo`, modelli, riconoscimento pista, apprendimento linea e `SessionAnalyzer`) è stato compilato con `kotlinc` durante la generazione del progetto.

## Limite attuale dell'MVP

L'analisi automatica cerca il miglioramento rispetto al **tuo giro ideale della sessione**. Per sapere dove perdi rispetto a un pilota da 31.5 a Morcone serve importare un giro di riferimento GPS/AiM. Questa è l'estensione successiva prevista: import XRK/CSV e generazione marker rispetto al reference lap esterno.


## Target pre-run

Il target è una soglia di tempo scelta dal pilota prima del run. Serve per dare un obiettivo esplicito alla sessione e per mostrare il distacco finale giro per giro. L'analisi box confronta anche BEST e IDEALE con il target.

Un semplice target numerico non basta, da solo, a calcolare un delta metrico istante-per-istante in ogni curva: per quello serve una traiettoria di riferimento associata a quel tempo. La prossima estensione prevista è il caricamento di un giro reference GPS/AiM (ad esempio un 31.500 reale a Morcone), che consentirà il delta live punto-per-punto contro quel giro.

## Build APK con GitHub Actions
Il repository include `.github/workflows/build-apk.yml`. Ogni push su `main`/`master` compila automaticamente `KartCoach.apk` e lo pubblica come artifact `KartCoach-APK`.
