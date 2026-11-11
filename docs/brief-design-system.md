# Prompt per il design system: app halba sui terminali di pagamento

Da incollare nella sessione del design system (artifact "Halba Design System").

---

Aggiungi al design system di halba un nuovo gruppo di componenti, **Terminal**, per l'app halba che gira sui terminali
di pagamento Android dei PSP. Usa token, tipografia, icone, loader con il marchio e tono della pagina di pagamento già
presente (Checkout), adattati a un terminale da banco. Le anteprime vanno in inglese, italiano e tedesco, come quelle
del checkout. Aggiungi anche una guida `project/terminal.md` con misure, token, testi e regole, così lo sviluppo
nativo Android (Kotlin, Jetpack Compose) può riprodurla senza interpretare.

## Contesto

- **Dispositivo:** un modello per PSP. Si parte da **Stripe Reader S700** (Android, schermo verticale da circa 5,5
  pollici, lettore di carte integrato). Poi myPOS e Adyen, con un modello ciascuno. Disegna in dp per uno schermo
  verticale di 360 × 640 dp, scalabile, e indica in `terminal.md` come si adatta ad altre dimensioni.
- **Uso:** terminale fisso al banco, **sempre in carica e con lo schermo sempre acceso**. Lo usano due persone: il
  commesso, che lo guarda da un braccio di distanza, e il cliente, che lo guarda da vicino per pagare.
- **Il pagamento parte dalla cassa del merchant**, non dal terminale: la cassa chiama l'API di halba e il terminale si
  sveglia. Il commesso tocca il terminale solo per la configurazione e per il menu del personale.
- **A riposo il terminale fa pubblicità al merchant:** mostra a schermo intero un'immagine o un video in loop caricato
  dal merchant nella dashboard. È un valore di marchio per il merchant e per halba.
- **Lettura della carta:** sui terminali Stripe la carta la legge l'app di Stripe (handoff). La sua schermata non è
  nostra, ma i passaggi da e verso la nostra app sì.
- **Il terminale resta nell'app halba:** lo garantisce la modalità kiosk del PSP. Non c'è un pulsante per uscire. Le
  funzioni del personale stanno in un menu nascosto protetto da PIN.

## Schermate e stati da disegnare

### A. Configurazione guidata (primo avvio)
1. **Benvenuto e lingua**: marchio halba, scelta tra English, Italiano, Deutsch.
2. **Abbinamento**:
   - codice di 8 caratteri molto grande, leggibile a un metro;
   - istruzione: "In halba, open Connections > your PSP > Add terminal and enter this code";
   - attesa, codice scaduto (con il nuovo codice), errore di rete.
3. **Negozio e nome**: elenco dei negozi del merchant con ricerca e codice del negozio; campo per il nome del
   terminale con un valore proposto ("Till 1").
4. **Prova del lettore**: collegamento in corso, lettore pronto, lettore non trovato (con Riprova e un aiuto breve).
5. **Schermo a riposo**: download dell'immagine o del video del merchant con avanzamento. Se il merchant non ne ha,
   un messaggio con l'anteprima dello schermo halba predefinito e dove caricarne uno.
6. **Pagamento di prova** (solo in modalità test, facoltativo): importo fisso, esito.
7. **Fatto**: riepilogo (negozio, nome, PSP) e "Start".

Mostra il progresso dei passi e un modo per tornare indietro.

### B. A riposo
- **Immagine o video del merchant a schermo intero**, in loop, senza bordi.
- **Un piccolo indicatore di stato** in sovrimpressione, discreto, in un angolo, che non rovini il contenuto del
  merchant. Ha tre varianti: online; offline (rosso); non in carica (avviso: "Connect the terminal to power").
- **Un piccolo marchio halba** facoltativo.
- **Schermo halba predefinito** quando il merchant non ha caricato niente: elegante, con il marchio halba e, se c'è,
  il logo del merchant.
- **Fasce sicure:** definisci le zone dove il merchant può mettere testi senza che l'indicatore li copra, e scrivile
  in `terminal.md`.

### C. Pagamento chiesto dalla cassa
- **Passaggio dal riposo al pagamento:** veloce e chiaro, perché il cliente deve capire subito che tocca a lui.
- **Importo grande** e riferimento dell'ordine piccolo.
- **Scelta del metodo**, solo se la cassa non l'ha già scelto: Carta, più i metodi con QR (Alipay, WeChat Pay, Bizum,
  BLIK, TWINT) con i marchi ufficiali, come nel checkout. Se la cassa ha già scelto il metodo, si salta direttamente
  allo stato D o E.

### D. Carta
- "Tap, insert or swipe the card" con l'importo.
- Passaggio verso il lettore Stripe e ritorno dal lettore.
- Elaborazione in corso, con il loader halba.

### E. QR
- Importo, marchio del metodo e istruzione nella lingua del metodo (es. "Scan with Alipay").
- QR grande con margine bianco, conto alla rovescia della scadenza (15 minuti), attesa del pagamento, QR scaduto.

### F. Esito (torna da solo al riposo dopo circa 4 secondi, con un indicatore del tempo)
- Approvato: importo, carta (circuito •••• ultime 4) o metodo, modalità di lettura (contactless, chip, QR).
- Non riuscito: motivo comprensibile al cliente ("Card declined", "Payment canceled") e cosa fare.
- Annullato dalla cassa.

### G. Menu del personale (nascosto)
- **Si apre con una pressione lunga di 3 secondi** sul marchio halba o sull'indicatore di stato. Mostra come si
  scopre senza che il cliente lo noti.
- **Tastierino PIN** di 6 cifre, grande. PIN sbagliato; blocco per 5 minuti dopo 5 tentativi.
- **Menu**:
  - informazioni sul terminale: negozio, nome, id, PSP, versione dell'app, online o offline, ultimo contatto;
  - rifai la configurazione;
  - aggiorna lo schermo a riposo;
  - scollega il terminale (con conferma);
  - esci dall'app (con conferma: "The till will not reach this terminal until the app is opened again").

### H. Stati di sistema
- Terminale disattivato dalla dashboard: serve un nuovo abbinamento.
- Rete assente: si tenta di nuovo da solo.
- Ripartenza dopo il riavvio notturno del terminale: breve, con il marchio.

## Vincoli

- **Bersagli** di almeno 56 dp. Nessun gesto complesso. Tastiera solo per il nome del terminale e la ricerca dei
  negozi.
- **Contrasto alto:** il terminale si usa con la luce del negozio e a volte all'aperto. I testi principali devono
  essere leggibili a 60–80 cm.
- **Animazioni** brevi e funzionali, con un'alternativa a movimento ridotto.
- **Accessibilità:** etichette per TalkBack e stati annunciati (pagamento approvato o rifiutato).
- **Lingue:** EN, IT, DE, con i formati di importo di ciascuna. I testi lunghi in tedesco non devono rompere il
  layout.
- **Colori del merchant** (facoltativo, da decidere): l'accento può prendere il colore principale del tema del
  checkout del merchant, con le stesse regole di contrasto della pagina di pagamento.

## Regole per l'immagine o il video del merchant (da scrivere in `terminal.md`)

Proponi i requisiti per lo schermo dell'S700, verticale 9:16, con la risoluzione in pixel da confermare sulle
specifiche del dispositivo:
- immagine: JPG, PNG o WebP, al massimo 5 MB;
- video: MP4 H.264, senza audio, al massimo 30 secondi e 30 MB, in loop senza stacchi;
- fasce sicure per testi e loghi.

La dashboard controllerà questi requisiti al caricamento e li mostrerà al merchant.

## Anche nella dashboard (componenti piccoli)

- **Caricamento dello schermo a riposo:** per tutto il workspace, sostituibile per singolo negozio. Contiene:
  - anteprima nella cornice del terminale;
  - requisiti del file ed errori di validazione;
  - stato della distribuzione ai terminali (es. "Downloaded by 3 of 4 terminals").
- **PIN del personale per negozio:** imposta o cambia, senza mostrarlo dopo il salvataggio.
- **Stato dei terminali** nella tabella: online, offline da X minuti, non in carica.

## Cosa consegnare

- Componenti e anteprime per gli stati A–H, più i componenti della dashboard, nel gruppo **Terminal** dell'artifact.
- `project/terminal.md` con:
  - misure in dp e sp e token usati;
  - testi nelle tre lingue;
  - comportamento dei passaggi e tempi (esito 4 s, QR 15 min, blocco PIN 5 min);
  - requisiti dell'immagine e del video.
