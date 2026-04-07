# Informazioni Tecniche - CountingGuard

Benvenuti nella documentazione tecnica di **CountingGuard**. Questo documento spiega il funzionamento interno del bot e l'architettura del codice.

## Posizionamento del Codice
Il codice sorgente completo, i file di configurazione Maven (`pom.xml`) e la logica applicativa sono disponibili esclusivamente nel **branch `master`** di questa repository.

---

## Architettura e Tecnologie
Il bot è sviluppato in **Java 21** utilizzando le seguenti tecnologie:

* **JDA (Java Discord API):** Per l'interfaccia con i Gateway di Discord e la gestione degli eventi.
* **SQLite:** Scelto come database per la sua leggerezza e velocità in ambienti a thread singolo o controllato.
* **HikariCP:** Un pool di connessioni JDBC ad alte prestazioni, utilizzato per gestire le letture e scritture sul database in modo asincrono senza bloccare il thread principale dei messaggi.
* **ExecutorService:** Il bot utilizza un pool di thread dedicato (`FixedThreadPool`) per processare le operazioni sul database, garantendo risposte rapide ai comandi slash anche sotto carico.

---

## Funzionamento del Core

### 1. Sistema di Validazione
Il bot monitora costantemente il canale di conteggio impostato. Ogni messaggio viene processato attraverso i seguenti controlli:
1.  **Verifica Autore:** Impedisce a un utente di contare due volte consecutivamente.
2.  **Verifica Formato:** Accetta solo numeri interi (Regex: `\d+`).
3.  **Verifica Sequenza:** Confronta il numero ricevuto con l'atteso (`ultimoNumero + 1`).

### 2. Gestione Database (WAL Mode)
Per garantire l'integrità dei dati e la velocità di risposta, il database SQLite opera in **Write-Ahead Logging (WAL) mode**. Questo permette letture e scritture simultanee più fluide, riducendo i tempi di attesa del database (`busy_timeout` impostato a 30s).

### 3. Sistema di Gradi e Punteggi
Il bot implementa una logica di progressione meritocratica:
-   **Punti:** Ogni numero corretto assegna +1 punto.
-   **Penalità:** Ogni errore comporta il reset del conteggio globale e una detrazione di 10 punti all'utente responsabile.
-   **Ruoli Automatici:** Al raggiungimento di specifiche soglie (Milestones personali), il bot assegna o rimuove automaticamente i ruoli Discord (es. *Contabile*, *Esattore*, *Imperatore*).

---

## Sicurezza e Moderazione
* **Blacklist:** Gestita sia su database che in una cache locale (`HashSet`) per un controllo istantaneo dei messaggi (migliaia al minuto).
* **Log di Sistema:** Ogni azione amministrativa e ogni errore di conteggio viene loggato in un canale staff dedicato per la massima trasparenza.
* **Token:** Gestito esclusivamente tramite variabili d'ambiente per prevenire fughe di credenziali.

---
**CodHub IT | Bot Development Team**
