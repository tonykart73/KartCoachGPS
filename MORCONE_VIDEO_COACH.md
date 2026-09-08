# Morcone Video Coach – v0.4

Questa versione NON usa e NON richiede la registrazione o l'importazione di un giro maestro.

La logica è stata aggiornata usando due fonti:

1. la telemetria XRK del Tony Kart 401 RR OK-N di Damiano;
2. il video del pilota professionista sullo stesso kart a Morcone.

## Regola tecnica incorporata

Dal video emerge una sequenza costante: una sola rotazione del kart, poi apertura del volante subito dopo il picco di rotazione. Il pilota professionista non continua a tenere sterzo aspettando la velocità minima; apre le mani e lascia usare al kart tutta l'uscita.

L'app ora usa questa regola in due modi.

### Punti Morcone calibrati

Sono incorporati due cue preventivi ad alta priorità:

- C5: `APRI` prima del punto in cui il volante deve iniziare ad aprirsi; obiettivo di percorrenza 71–72 km/h e raggio più grande.
- C6: `APRI` prima del punto di apertura; obiettivo: non aspettare la minima, liberare il volante e usare tutta l'uscita.

I punti sono coordinate del circuito, non un giro GPS importato.

### Coach dinamico

Fuori dai due punti calibrati, il telefono osserva l'andamento dell'heading GPS e della velocità. Quando riconosce un picco di rotazione in una curva significativa, può mostrare `APRI` appena il picco è passato oppure se il pilota mantiene troppo a lungo la rotazione.

I messaggi possibili sono:

- `VOLANTE · NON ASPETTARE LA MINIMA`
- `VOLANTE · NON TENERE STERZO`
- `VOLANTE · USA L'USCITA`

La logica dinamica è attiva soltanto sul profilo Morcone e non sostituisce l'analisi automatica dei giri già presente nell'app.
