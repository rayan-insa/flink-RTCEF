# Roadmap — RTCEF × Wayeb × Flink (atomic, pushable, trackable)

## 0) Baseline & hygiene (pushable repo)
### 0.1 State of the repo
- Clarifier “ce qui est source” vs “ce qui est généré”
- Définir la politique de versioning (tags/branches/releases)
- Définir la structure cible (où vivent scripts, assets, docs, outputs)

### 0.2 Git hygiene
- Vérifier que Wayeb (source) est bien versionné
- S’assurer que tout artefact build/log/output est ignoré
- Ajouter un dossier “results” versionné uniquement via placeholders (pas de fichiers volumineux)
- S’assurer que rien de sensible ne traîne (tokens, configs locales)

### 0.3 Reproductibilité
- Documenter les prérequis (Java/Scala/SBT/Flink version)
- Documenter le “happy path” de build
- Définir un “smoke test” minimal stable (pattern + stream + expected behavior)
- Définir un “golden output” logique (pas forcément un fichier binaire, plutôt des assertions de contenu)

Deliverable attendu:
- Repo propre, reproductible, pas d’artefacts générés, doc minimale “comment exécuter”

---

## 1) Step 1 — Wayeb CLI smoke test (compile + recognition + stats) ✅/⬜
Objectif: Avoir un test minimal qui valide la chaîne “pattern -> fsm -> recognition -> stats écrites”.

### 1.1 Inputs minimaux
- Un pattern SRE minimal validé (ex: SEQ(A,B))
- Un stream minimal validé (ex: A puis B)
- Un emplacement de sortie stable pour stats + fsm

### 1.2 Exécution stable
- Vérifier que la compilation produit bien un FSM
- Vérifier que la recognition détecte un match attendu
- Vérifier que l’écriture de stats produit exactement un fichier attendu, au bon nom, sans duplication inattendue

### 1.3 Validation des outputs
- Valider les colonnes du CSV de stats (schema attendu)
- Valider que le nombre de lignes correspond à une exécution (pas d’append non voulu)
- Verrouiller les conventions de nommage (prefix vs suffix)

Deliverable attendu:
- Script smoke test stable + doc “ce que ça prouve” + conventions sur outputs

---

## 2) Step 2 — Stabiliser l’interface “stats” (format, schema, invariants)
Objectif: Définir clairement ce que signifient les stats et comment les exploiter côté Flink.

### 2.1 Définir le schema final des stats
- Colonnes (noms, types, unités)
- Invariants (ex: execTime en ns, streamSize en events, etcef)
- Stratégie multi-runs (append vs overwrite)

### 2.2 Définir les “stats utiles” pour RTCEF
- Latence end-to-end
- Throughput
- Nb de matches
- (Optionnel) métriques par pattern si multi-pattern

### 2.3 Design “compatibilité”
- Compatibilité back: conserver un mode legacy si besoin
- Compatibilité forward: permettre d’ajouter des colonnes sans casser le parsing

Deliverable attendu:
- Spec claire du CSV (ou JSON si tu changes) + règles d’écriture + règles de parsing

---

## 3) Step 3 — Définir l’API d’intégration Wayeb ↔ Flink (contrat, pas implémentation)
Objectif: Décrire comment Flink va appeler Wayeb / intégrer Wayeb.

### 3.1 Choix du mode d’intégration (1 seul au départ)
- Mode A: “embedded library” (Wayeb appelé comme lib Scala/Java)
- Mode B: “sidecar process” (Wayeb en process séparé, IO stream)
- Mode C: “microservice” (HTTP/gRPC)

### 3.2 Contrat des échanges
- Format des événements entrants (champs minimum, timestamp, type)
- Gestion du temps (event time vs processing time)
- Gestion du watermarks (si event time)
- Gestion du backpressure (si streaming)

### 3.3 Contrat des sorties
- Format des matches (patternId, match timestamp, payload minimal)
- Format des métriques (stats)
- Sémantique de “countPolicy” et sélection (overlap, skip-till-next, etc.)

Deliverable attendu:
- Document “Integration Contract” (inputs/outputs/semantics)

---

## 4) Step 4 — Prototype minimal Flink (source -> opérateur -> sink)
Objectif: Faire tourner Flink avec un flux, sans complexité, et un hook Wayeb.

### 4.1 Pipeline Flink minimal
- Source: flux contrôlé (fichier / générateur)
- Opérateur: pass-through d’abord, puis hook Wayeb
- Sink: impression / fichier / compteur

### 4.2 Encapsulation du hook Wayeb
- Définir une interface interne (ex: processEvent -> maybeMatches)
- Supporter 1 pattern d’abord (mono-pattern)
- Vérifier que le “match” se produit au bon endroit dans le pipeline

### 4.3 Observabilité minimale
- Compteurs (events ingérés, matches)
- Temps de traitement (micro-mesures)

Deliverable attendu:
- Job Flink minimal stable + preuve de match + métriques mini

---

## 5) Step 5 — “Real-time constraints” & bounded overhead (mesure, puis optimisation)
Objectif: Mesurer puis réduire l’overhead Wayeb dans le pipeline.

### 5.1 Baseline non-spéculative (référence)
- Baseline 1: pipeline Flink sans Wayeb (noop operator)
- Baseline 2: pipeline Flink avec Wayeb “disabled” (parsing/dispatch seulement)
- Baseline 3: pipeline Flink avec Wayeb “full”

### 5.2 Métriques à collecter
- Latence par event (p50/p95/p99)
- Throughput stable
- CPU/mémoire (approx)
- Jitter et variance

### 5.3 Identifier les goulets
- Parsing events
- Allocation/mémoire
- Synchronisation/verrous
- Coût de matching

Deliverable attendu:
- Rapport baseline (tableaux + graphs hors code) + priorités d’optimisation

---

## 6) Step 6 — Multi-pattern & dynamic patterns
Objectif: Supporter plusieurs patterns et/ou le chargement dynamique.

### 6.1 Multi-pattern statique
- Compiler plusieurs patterns en un “bundle”
- Router les matches avec patternId
- Stats par pattern (si nécessaire)

### 6.2 Chargement/refresh
- Définir un mécanisme de reload (runtime)
- Définir la cohérence (qu’advient-il des runs en cours)
- Définir les garanties (at-least-once matches vs exactly-once, etc.)

Deliverable attendu:
- Support multi-pattern + doc sur sémantique de reload

---

## 7) Step 7 — Event time, watermarks, et fenêtres
Objectif: Aligner Wayeb avec une sémantique Flink réaliste.

### 7.1 Sémantique temporelle
- Choisir event time ou processing time (au départ)
- Définir comment Wayeb interprète timestamps
- Définir le comportement out-of-order

### 7.2 Watermarks
- Définir le lien watermark -> “finalité” des matches
- Définir ce qui est “late”
- Stratégie de handling late events

### 7.3 Fenêtrage / bounding state
- Définir les règles de purge/TTL
- Éviter explosion mémoire

Deliverable attendu:
- Sémantique temps/watermarks documentée + tests

---

## 8) Step 8 — Reliability semantics (exactly-once / checkpointing)
Objectif: Proposer une sémantique de tolérance aux pannes cohérente.

### 8.1 Choix de garantie
- At-least-once (plus simple)
- Exactly-once (complexe, mais possible)

### 8.2 Checkpointing
- Définir l’état à snapshot (runs/automates)
- Définir comment restaurer sans duplications de matches
- Définir l’idempotence des sinks

Deliverable attendu:
- Design doc “fault tolerance semantics” + implémentation minimaliste

---

## 9) Step 9 — Packaging & demos reproductibles
Objectif: Avoir une démo propre, exécutable et présentable.

### 9.1 Artifacts
- Build reproductible
- Versioning clair
- Outputs de demo séparés (non versionnés)

### 9.2 Demos
- Demo 1: mono-pattern simple
- Demo 2: multi-pattern
- Demo 3: out-of-order + watermarks (si applicable)

### 9.3 Documentation
- README top-level projet (vision + quickstart)
- README technique (architecture + contrats + limites)
- “Known issues” + “Next steps”

Deliverable attendu:
- Démo stable + docs + structure propre

---

## 10) Step 10 — Expérimentation & résultats (paper-grade)
Objectif: Faire les expériences de manière “publishable”.

### 10.1 Experimental plan
- Variables contrôlées (charge, patterns, tailles stream)
- Mesures répétées (variance)
- Environnement fixe (versions, machine)

### 10.2 Comparaisons
- Baseline(s) définies
- Configurations Wayeb/Flink paramétrées
- Résultats agrégés (p50/p95/p99, throughput, overhead)

### 10.3 Write-up
- Méthodologie
- Résultats
- Menaces à la validité
- Conclusions

Deliverable attendu:
- Dataset de résultats (non versionné ou compressé), figures, section “Evaluation”

---

# Tracking (simple checklist)
- [ ] 0 Baseline & hygiene
- [x] 1 Wayeb CLI smoke test stable
- [ ] 2 Stabiliser schema stats
- [ ] 3 Contrat d’intégration
- [ ] 4 Prototype Flink + hook Wayeb
- [ ] 5 Mesures & overhead
- [ ] 6 Multi-pattern / dynamic
- [ ] 7 Event time / watermarks
- [ ] 8 Fault tolerance
- [ ] 9 Packaging & demos
- [ ] 10 Expérimentation paper-grade