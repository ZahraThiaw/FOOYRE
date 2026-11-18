# FOOYRE — Cahier des charges

> **La lumière intelligente de votre entreprise.**
> Gérez. Analysez. Anticipez. Développez.

Version : 0.3 (Phase 0 consolidée — 2026-11-18, arbitrages O1, O3–O6 et H8–H11 intégrés) · Statut : **décisions D1 à D20 intégrées** ; les points encore ouverts (dont O2, fiscalité) sont listés au §8.

---

## 1. Contexte et vision

FOOYRE est une plateforme SaaS de gestion intelligente destinée principalement aux **femmes entrepreneures** et aux petites entreprises au Sénégal (boutiques, commerces de détail, vente en ligne via WhatsApp/Instagram, petite restauration, cosmétiques, prêt‑à‑porter, etc.).

Le produit doit aller au‑delà de l'enregistrement de ventes : il centralise la gestion commerciale et financière, rend la performance lisible et aide à décider grâce à une IA qui **explique** des chiffres calculés de façon fiable par le système.

### 1.1 Utilisateurs cibles (personas)

| Persona | Besoin principal | Contraintes |
|---|---|---|
| Entrepreneure individuelle (vente en ligne, WhatsApp) | Savoir ce qu'elle a vendu, ce qu'on lui doit, ce qui reste en stock | Utilise surtout un smartphone, connexion mobile parfois instable |
| Gérant·e de boutique avec 1 à 5 employés | Contrôler les ventes des employés, les stocks, la caisse | Plusieurs utilisateurs, besoin de droits différenciés |
| Propriétaire multi‑activités | Vision consolidée, rentabilité réelle | Peut appartenir à plusieurs entreprises (décision D6) |
| Administrateur·rice de la plateforme (FOOYRE) | Gérer les entreprises clientes, formules, support | Ne doit pas accéder aux données métier sans justification |

### 1.2 Contexte local à prendre en compte

- Devise principale : **franc CFA (XOF)**, sans subdivision utilisée en pratique (montants entiers).
- Moyens de paiement courants : espèces, **Wave**, **Orange Money**, Free Money, virement, carte (rare), crédit client (vente à crédit très fréquente).
- Fuseau horaire : `Africa/Dakar` (UTC+0, pas d'heure d'été).
- Usage majoritairement **mobile** : l'interface doit être conçue *mobile‑first*.
- Langue de l'interface : **français** au lancement ; architecture prévue pour ajouter le wolof, le pulaar, le sérère et d'autres langues nationales (décision D16, §2.11).
- Protection des données : loi sénégalaise n° 2008‑12 sur les données à caractère personnel, contrôlée par la CDP (Commission de Protection des Données Personnelles). Mesures techniques et validations juridiques distinguées au §5.1.

---

## 2. Périmètre fonctionnel

### 2.1 FOOYRE GESTION
- Catégories de produits (hiérarchie simple, un niveau de parent).
- Produits : nom, SKU/référence, code‑barres optionnel, unité (pièce, kg, litre…), prix de vente, coût d'achat, seuil d'alerte de stock, statut actif/archivé, image optionnelle.
- Stocks : quantité disponible calculée à partir d'un **journal de mouvements** traçable (entrée, sortie, ajustement, annulation). Toute correction se fait par un **mouvement compensatoire**, jamais par modification d'un mouvement existant.
- Approvisionnements (réceptions fournisseurs) : met à jour le stock et recalcule le **coût moyen pondéré (CMP)** (règles au §3.1).
- Inventaire / ajustement de stock avec motif obligatoire.
- **Stock négatif interdit par défaut.** Chaque entreprise peut l'autoriser explicitement dans ses paramètres. Lorsque l'option est désactivée, toute vente dépassant le stock disponible est refusée par le backend (contrôle transactionnel sous verrou).
- Clients : nom, téléphone, email optionnel, adresse, notes, solde dû.
- Fournisseurs : nom, contact, téléphone, notes.

### 2.2 FOOYRE COMMERCE
- Ventes (comptoir ou commande) avec lignes, remises, total.
- Commandes : vente avec statut de préparation/livraison (brouillon → confirmée → livrée).
- Paiements multiples et **partiels** par vente, avec moyen de paiement.
- Ventes à crédit et suivi des créances clients.
- **Annulation** d'une vente (jamais de suppression) avec motif ; annulation **totale** de la vente uniquement au MVP (les retours partiels de marchandises sont hors périmètre, §2.9). Décision D11 :
  - l'historique est conservé (la vente passe au statut annulé, rien n'est effacé) ;
  - si la vente avait été (partiellement) payée, le montant payé est restitué au choix par **remboursement total ou partiel** et/ou par un **avoir client** ; modalités détaillées encore ouvertes (O7) ;
  - le remboursement est enregistré dans la **trésorerie** (décaissement) ; l'avoir est **suivi séparément** (il n'est ni un encaissement ni un décaissement) ;
  - le stock n'est réintégré que pour les produits retournés **revendables** (choix ligne par ligne) ; le traitement comptable des marchandises non revendables reste à valider (O8) ;
  - l'annulation est enregistrée dans la **période où elle intervient** (D13, §3).
- **Avoirs clients** : rattachés à un client identifié, numérotés, utilisables comme moyen de règlement d'une vente ultérieure du même client, avec un journal de mouvements (émission, utilisation, contre‑passation).
- **Remises** : propriétaire et gestionnaire sans limite ; l'employé uniquement dans une **limite configurée** par le propriétaire ou le gestionnaire, contrôlée par le serveur et journalisée (décision D14 ; forme exacte de la limite : O11).
- Chaque ligne de vente conserve une **photographie historique** du prix de vente, de la remise, du taux de TVA et du coût unitaire applicables au moment de la confirmation.
- La confirmation d'une vente met à jour le stock de manière **atomique** (même transaction que la vente) et protégée contre les opérations concurrentes (pas de double déduction).
- Historique d'achats par client.
- Reçus numérotés séquentiellement par entreprise (affichage, impression, PDF, partage).

### 2.3 FOOYRE FINANCE
- Dépenses catégorisées (loyer, salaires, transport, électricité, marketing…), avec statut payé / à payer.
- Comptes de trésorerie (caisse, Wave, Orange Money, banque…) avec solde d'ouverture.
- Journal des encaissements et décaissements.
- Indicateurs financiers calculés par le backend (voir §3).

### 2.4 FOOYRE INTELLIGENCE
- Assistant IA (Claude, côté backend uniquement) qui :
  - identifie les tendances commerciales ;
  - détecte les produits peu rentables ;
  - signale les risques de rupture de stock ;
  - explique les performances de la période ;
  - formule des recommandations concrètes et actionnables.
- L'IA travaille **uniquement** sur des données agrégées calculées par le backend ; elle ne calcule pas les chiffres de référence et ne doit jamais en inventer.
- Si les données sont insuffisantes, elle le dit explicitement.
- Pas d'accès direct de l'IA à la base de données : elle ne reçoit que ce que le backend lui transmet.
- Quotas et plafonds de consommation par entreprise, suivi des jetons et des coûts.
- Modèle par défaut : **Claude Opus 5.5** (décision D9), configurable.
- **Désactivée par défaut** pour chaque entreprise ; activée uniquement après **consentement explicite du propriétaire**, révocable à tout moment (D15).
- Seules les données strictement nécessaires sont transmises, sans donnée personnelle de client pour les analyses ordinaires (D18).
- **La fonctionnalité n'est pas activée en production avant les validations juridiques et réglementaires du §5.1.**

### 2.5 FOOYRE COMMERCE DIGITAL
- Catalogue public partageable (lien unique par entreprise, compatible partage WhatsApp).
- Sélection des produits visibles publiquement.
- Formulaire de **demande de commande** (nom, téléphone, produits, quantités, message), sans paiement en ligne.
- Les demandes arrivent dans un tableau de bord et peuvent être converties en vente par l'entreprise.

### 2.6 TABLEAU DE BORD
- Indicateurs clés de la période (ventes nettes ajustées avec le détail ventes brutes / annulations, encaissements, dépenses, marge brute, résultat net de gestion, trésorerie, avoirs en cours).
- Graphiques d'évolution (Recharts), top produits, top clients.
- Alertes : stock bas, créances anciennes, dépenses inhabituelles.

### 2.7 RAPPORTS
- Rapports PDF et CSV : ventes (brutes, annulations / ajustements, nettes ajustées), stocks, dépenses, synthèse financière, créances clients, avoirs.
- Filtres par période. Un rapport d'une période passée n'est pas modifié par les annulations ou corrections ultérieures (D13).

### 2.8 Administration de la plateforme
- Gestion des entreprises clientes (activation, suspension).
- Gestion des formules et des quotas (sans intégration de paiement à ce stade).
- Journal d'audit.
- **Aucun accès automatique aux données métier des entreprises.** Un accès exceptionnel n'est possible que dans le cadre d'un **accès support** (décision D12) :
  - uniquement après **approbation explicite du propriétaire** de l'entreprise concernée ;
  - **justification** obligatoire, **durée limitée**, **périmètre défini** (domaines consultables) ;
  - **lecture seule par défaut** ; un accès en écriture n'est possible que si le propriétaire l'autorise explicitement lors de son approbation ;
  - **journalisation** de chaque consultation et action, consultable par le propriétaire ;
  - **révocable** à tout moment par le propriétaire.
  Aucun accès silencieux ou permanent.

### 2.9 Hors périmètre (à ce stade)
- Paiement des abonnements en ligne.
- Intégration API Wave / Orange Money (les moyens de paiement sont seulement **enregistrés**).
- Comptabilité légale complète (SYSCOHADA), déclarations fiscales.
- Application mobile native (le web responsive / PWA est privilégié).
- Mode hors ligne **complet** : seul un mode hors ligne **partiel** est prévu (D17, §2.10).
- Retours partiels de marchandises / annulation partielle d'une vente (le type de mouvement `CUSTOMER_RETURN_IN` est réservé pour plus tard). Les avoirs émis lors d'une annulation totale sont, eux, dans le périmètre (D11).
- Gestion de la TVA déductible sur achats et déclarations de TVA.
- Interface vocale : évolution future à évaluer techniquement, sans engagement de prise en charge (D16).

### 2.10 Mode hors ligne partiel — décision D17
- **Consultation** des dernières données synchronisées, la date de dernière synchronisation étant toujours affichée.
- **Mise en attente locale** de certaines opérations autorisées (liste définitive à arbitrer : O10), synchronisées au retour de la connexion.
- Une opération saisie hors ligne reste **en attente** tant que le serveur ne l'a pas validée. **Aucune validation définitive côté client** : pas de numéro de reçu définitif, pas de déduction de stock faisant foi, aucun effet sur les indicateurs.
- Synchronisation **idempotente** : une opération rejouée plusieurs fois n'est enregistrée qu'une fois.
- **Détection des conflits** (prix modifié, stock insuffisant, droits retirés…) et **résolution explicite** par un utilisateur autorisé ; aucune fusion silencieuse d'opérations financières.
- **Cohérence du stock** : le serveur réapplique toutes les règles (D5, verrous) au moment de la synchronisation.
- **Protection et expiration des données locales** : données minimales, cloisonnées par utilisateur et par entreprise, effacées à la déconnexion, durée de conservation limitée.
- Ne sont pas proposées hors ligne, sauf décision contraire (O10) : annulations, remboursements, ajustements de stock, paramètres, membres, accès support, IA, opérations de trésorerie.

### 2.11 Langues — décision D16
- **Français** comme langue initiale de l'interface, des documents (reçus, rapports) et des analyses IA.
- Architecture **extensible**, pour ajouter ensuite le **wolof**, le **pulaar**, le **sérère** et d'autres langues nationales sans refonte :
  - textes externalisés dans des catalogues de traduction dès la phase 1 ;
  - préférence de langue par utilisateur ;
  - formats de dates et de montants gérés par locale.
- La prise en charge de ces langues par les formats régionaux des navigateurs et la qualité des réponses de l'IA dans ces langues **ne sont pas vérifiées** ; elles devront être évaluées avant toute ouverture.
- La **voix** (saisie ou restitution vocale) est une évolution future à évaluer techniquement ; aucune prise en charge n'est promise.

---

## 3. Définitions financières (référentiel obligatoire)

Ces définitions sont **normatives** : le code, les écrans, les rapports et les prompts IA doivent les respecter. Toute période s'entend en heure de Dakar, bornes incluses au jour près.

**Principe de période (décision D13)** : les annulations et corrections sont enregistrées dans la **période où elles interviennent** (date de l'annulation, de la contre‑passation, du remboursement). Les chiffres d'une période passée ne sont **jamais** modifiés par une opération ultérieure : un rapport historique recalculé redonne le même résultat. Les rapports distinguent **ventes brutes**, **annulations / ajustements** et **ventes nettes ajustées**. Seule une saisie antidatée pourrait modifier une période passée : son encadrement est un point ouvert (O9).

| Indicateur | Définition | Source |
|---|---|---|
| **Ventes brutes (CA brut)** | Somme des montants nets de ligne (après remises, hors taxes si TVA activée) des ventes **confirmées** dont la **date de vente** est dans la période, y compris celles annulées ultérieurement. | `sale`, `sale_line` |
| **Annulations / ajustements de ventes** | Somme des mêmes montants pour les ventes annulées dont la **date d'annulation** est dans la période (quelle que soit leur date de vente). Présentée en négatif. | `sale.cancellation_date` |
| **Ventes nettes ajustées (CA)** | Ventes brutes − annulations / ajustements de la période. C'est l'indicateur « Chiffre d'affaires » affiché par défaut. Indépendant des paiements. | calculé |
| **Encaissements** | Paiements clients reçus en moyens de trésorerie, datés dans la période (date du paiement), − remboursements datés dans la période − contre‑passations de paiements datées dans la période (date de la contre‑passation). Une vente à crédit génère du CA mais pas d'encaissement. Ni la **conversion d'un paiement en avoir** ni l'**utilisation d'un avoir** ne sont des encaissements (aucun mouvement de trésorerie). | `cash_movement` |
| **Dépenses** | Dépenses d'exploitation (charges) datées dans la période − dépenses annulées dont la date d'annulation est dans la période. Hors achats de marchandises destinés à la revente. | `expense` |
| **Coût des marchandises vendues (CMV)** | CMV brut (somme, sur les lignes des ventes brutes de la période, de *quantité × coût unitaire figé au moment de la vente* — CMP applicable à cet instant, décision D1) − coûts des marchandises **réintégrées en stock** lors des annulations de la période (au coût figé). Le coût des marchandises non revendables n'est pas réintégré ; sa présentation comptable reste à valider (O8). | `sale_line.unit_cost`, `sale_cancellation_line` |
| **Marge brute** | Ventes nettes ajustées − CMV. **N'est jamais présentée comme un bénéfice.** | calculé |
| **Taux de marge brute** | Marge brute / ventes nettes ajustées (non défini si elles sont nulles ou négatives). | calculé |
| **Résultat net de gestion** | Marge brute − Dépenses de la période. Indicateur de gestion simplifié (pas d'amortissements, pas d'impôt) ; libellé à afficher : « Résultat net (estimation de gestion) ». C'est la seule notion qui puisse être appelée « bénéfice » dans l'interface, toujours avec cette mention d'estimation. | calculé |
| **Trésorerie disponible** | Pour chaque compte : solde d'ouverture + entrées − sorties (dépenses payées, paiements fournisseurs, remboursements, contre‑passations) jusqu'à la date donnée. Somme des comptes. | `cash_movement` |
| **Créances clients** | Pour chaque client : Σ (montant total − montant réglé) des ventes confirmées non annulées. | calculé |
| **Avoirs clients en cours** | Σ soldes restants des avoirs non épuisés. Montant dû **aux** clients, présenté séparément des créances (pas de compensation automatique). | `customer_credit` |
| **Valeur du stock** | Σ quantité en stock (positive) × coût moyen pondéré courant. | `product` |

Règles complémentaires :
- Les achats de marchandises (approvisionnements) **ne sont pas** des dépenses : ils augmentent le stock, puis deviennent du CMV lors de la vente. Leur paiement est un **décaissement** de trésorerie.
- Une vente annulée reste dans les ventes brutes de sa période d'origine ; son annulation apparaît dans la période d'annulation (D13). Les remboursements sont des sorties de trésorerie datées du jour du remboursement.
- Une vente réglée en tout ou partie par avoir génère du CA ; la part réglée par avoir réduit la créance mais n'est pas un encaissement.
- La TVA collectée suit le même principe (ventes brutes, annulations de la période) ; son traitement fiscal reste soumis à O2.
- Les montants sont calculés en `BigDecimal` côté Java et stockés en `NUMERIC` côté PostgreSQL ; jamais `float`/`double`.
- Tous ces calculs sont **centralisés dans le backend** ; ni le frontend ni l'IA ne produisent de chiffres de référence.

### 3.1 Coût moyen pondéré (CMP) — décision D1

- Le CMP d'un produit est recalculé à chaque **entrée de stock valorisée** (réception d'approvisionnement, stock initial, ajustement positif avec coût saisi) :
  `CMP_nouveau = (Q_stock × CMP_actuel + Q_entrée × coût_entrée) / (Q_stock + Q_entrée)`.
- Si le stock avant l'entrée est **nul ou négatif** (possible uniquement si l'entreprise autorise le stock négatif) ou si le CMP actuel est inconnu, `CMP_nouveau = coût_entrée`.
- Les sorties (ventes, ajustements négatifs) ne modifient pas le CMP ; elles sont valorisées au CMP courant.
- Les annulations de vente réintègrent le stock **au coût figé sur la ligne de vente**, uniquement pour les lignes déclarées **revendables** (D11) ; le CMP est recalculé comme pour une entrée. Les lignes non revendables ne génèrent aucun mouvement de stock.
- L'annulation d'un approvisionnement déjà reçu crée un mouvement compensatoire de sortie au coût de l'achat ; si du stock reste, le CMP est recalculé en retirant cette valeur, sinon il est conservé. Les coûts déjà figés sur des ventes passées ne sont **jamais** modifiés rétroactivement.
- Si la TVA est activée, les coûts d'achat sont saisis et stockés **hors taxes**.
- Précision : CMP stocké sur 4 décimales, arrondi `HALF_UP`. Si le coût d'un produit est inconnu, la vente reste possible, mais elle est signalée comme « coût inconnu » dans les indicateurs de qualité des données (la marge correspondante n'est pas présentée comme fiable).

### 3.2 TVA, prix HT/TTC et arrondis — décision D4 (principes) · règles de calcul **provisoires** (O2)

> ⚠️ **Règles provisoires, non validées fiscalement.** Les principes ci‑dessous (TVA désactivée par défaut, activable par entreprise, mode HT/TTC, taux configurable, calculs centralisés) sont validés (D4). En revanche, l'**algorithme de calcul par ligne, la ventilation des remises et les règles d'arrondi** sont une proposition technique en attente de validation par un·e comptable (point ouvert O2). Ils ne doivent jamais être présentés comme des règles fiscales définitives, ni dans l'interface ni dans la documentation utilisateur.

- La TVA est **désactivée par défaut**. Chaque entreprise peut l'activer ou la désactiver dans ses paramètres ; aucun régime fiscal unique n'est imposé.
- **Mode de saisie des prix configurable** par entreprise : `HT` ou `TTC`.
- **Taux par défaut proposé : 18 %**, modifiable par entreprise ; un taux spécifique peut être défini par produit.
- Le mode de saisie et le taux sont **figés sur chaque vente / ligne de vente** au moment de la confirmation : un changement de paramètre n'altère jamais l'historique.
- TVA désactivée : `montant HT = montant TTC = prix × quantité − remises`, TVA = 0.
- Calcul **par ligne** (ordre de référence **provisoire**, O2) :
  1. `brut_ligne = prix_unitaire × quantité` ;
  2. `net_saisi = brut_ligne − remise_ligne − part de la remise globale` (la remise globale est ventilée au prorata des montants nets de ligne, le reliquat d'arrondi étant affecté à la ligne de plus fort montant) ;
  3. mode HT : `HT = net_saisi` ; `TVA = arrondi(HT × taux)` ; `TTC = HT + TVA` ;
     mode TTC : `TTC = net_saisi` ; `HT = arrondi(TTC / (1 + taux))` ; `TVA = TTC − HT`.
  4. Totaux de la vente = **somme des lignes arrondies** (jamais recalculés à partir du total).
- **Arrondi** : `RoundingMode.HALF_UP` à l'échelle de la devise (XOF : 0 décimale, conformément à l'ISO 4217) ; calculs intermédiaires sans arrondi (précision `BigDecimal` suffisante, `MathContext.DECIMAL128` pour les divisions).
- Ces règles font l'objet de tests unitaires dédiés (cas HT, TTC, remises, taux multiples, TVA désactivée, annulation dans une autre période). Leur conformité fiscale **n'est pas validée** : elle doit être confirmée par un·e comptable (point ouvert O2) ; toute correction issue de cette validation sera appliquée dans `TaxCalculator` uniquement.

---

## 4. Rôles et permissions — décision D3

Principes validés :
- **BUSINESS_OWNER** (propriétaire) : droits complets sur son entreprise.
- **BUSINESS_MANAGER** (gestionnaire) : permissions opérationnelles et financières selon la matrice ci‑dessous ; peut modifier les informations générales de l'entreprise et **consulter** les paramètres financiers et sensibles **sans pouvoir les modifier** (décision D20).
- **EMPLOYEE** (employé) : uniquement les fonctionnalités nécessaires à son travail ; **aucun accès** aux coûts d'achat, marges et données financières sensibles ; **ne peut pas annuler une vente**. Il peut **consulter les ventes de son entreprise** (sans coûts, marges ni données financières sensibles) et appliquer des **remises dans la limite configurée** par le propriétaire ou le gestionnaire (décision D14).
- **PLATFORM_ADMIN** : aucun accès automatique aux données métier (accès support encadré uniquement, §2.8, D12).
- **Paramètres financiers et sensibles** (TVA, mode HT/TTC, taux, stock négatif, devise) : modifiables **uniquement par le propriétaire** (D20).
- **Gestion des membres** (invitations, rôles, révocations) : selon la permission `MEMBER_MANAGE`, réservée au propriétaire dans la matrice actuelle ; toute délégation au gestionnaire serait une modification de la matrice, à décider explicitement.
- Les rôles d'entreprise sont portés par l'appartenance (`BusinessMembership`) : un même utilisateur peut avoir des rôles différents dans des entreprises différentes.
- **Toutes les autorisations sont vérifiées côté backend.** Masquer un bouton dans le frontend n'est jamais une mesure de sécurité.

Matrice fonctionnelle (✅ autorisé · ❌ refusé · 👁 lecture seule). Les codes de permissions techniques correspondants sont dans `docs/architecture.md` §5.3.

| Capacité | OWNER | MANAGER | EMPLOYEE | PLATFORM_ADMIN |
|---|:-:|:-:|:-:|:-:|
| Gérer les entreprises clientes, formules, quotas | ❌ | ❌ | ❌ | ✅ |
| Données métier d'une entreprise | ✅ | ✅ | partiel (ci‑dessous) | ❌ (sauf accès support) |
| Gérer les membres et les rôles, invitations | ✅ | ❌ | ❌ | ❌ |
| Informations générales de l'entreprise (nom, contacts, logo, langue par défaut) | ✅ | ✅ | 👁 | ❌ |
| Paramètres financiers et sensibles (TVA, mode HT/TTC, taux, stock négatif, devise) — modifier | ✅ | ❌ | ❌ | ❌ |
| Paramètres financiers et sensibles — consulter | ✅ | 👁 | ❌ (uniquement les informations nécessaires à la saisie d'une vente : devise, TVA activée, mode HT/TTC, sa limite de remise) | ❌ |
| Limite de remise des employés — configurer | ✅ | ✅ | ❌ | ❌ |
| Autoriser / révoquer un accès support, consentement IA | ✅ | ❌ | ❌ | ❌ |
| Catalogue public (paramétrage) | ✅ | ✅ | ❌ | ❌ |
| Produits et catégories | ✅ | ✅ | 👁 (sans coûts) | ❌ |
| Fournisseurs, approvisionnements | ✅ | ✅ | ❌ | ❌ |
| Voir coûts d'achat, CMP, marges, valeur du stock | ✅ | ✅ | ❌ | ❌ |
| Consulter le stock disponible et les alertes de stock | ✅ | ✅ | ✅ | ❌ |
| Ajustements de stock | ✅ | ✅ | ❌ | ❌ |
| Clients : créer, modifier, consulter (fiche, historique des ventes sans coûts) | ✅ | ✅ | ✅ | ❌ |
| Clients : consulter les soldes dus et les avoirs | ✅ | ✅ | ❌ | ❌ |
| Clients : archiver | ✅ | ✅ | ❌ | ❌ |
| Créer / confirmer une vente, encaisser un paiement | ✅ | ✅ | ✅ | ❌ |
| Appliquer une remise lors d'une vente | ✅ | ✅ | ✅ dans la limite configurée (contrôle serveur, journalisé) | ❌ |
| Modifier le prix unitaire catalogue lors d'une vente | ✅ | ✅ | ❌ | ❌ |
| Annuler une vente (remboursement et/ou avoir, choix de réintégration), rembourser, contre‑passer un paiement | ✅ | ✅ | ❌ | ❌ |
| Régler une vente avec un avoir client | ✅ | ✅ | ❌ (provisoire, voir O7) | ❌ |
| Consulter les ventes de l'entreprise | ✅ | ✅ | ✅ sans coûts, marges ni données financières sensibles | ❌ |
| Demandes de commande du catalogue (traiter, convertir) | ✅ | ✅ | ✅ | ❌ |
| Dépenses, comptes et transferts de trésorerie | ✅ | ✅ | ❌ | ❌ |
| Apports / retraits du propriétaire, ajustements de trésorerie | ✅ | ❌ | ❌ | ❌ |
| Tableau de bord financier, rapports | ✅ | ✅ | ❌ (récapitulatif de ses ventes du jour) | ❌ |
| Assistant IA | ✅ | ✅ | ❌ | ❌ |

---

## 5. Exigences non fonctionnelles

- **Sécurité** : isolation stricte des données entre entreprises, y compris face à la manipulation d'identifiants ; OWASP Top 10 ; mots de passe hachés (Argon2id ou bcrypt) ; HTTPS obligatoire en production.
- **Fiabilité des données** : historique immuable des ventes, paiements et mouvements de stock ; corrections par écritures inverses, jamais par suppression.
- **Performance** : écrans principaux < 2 s sur réseau 3G/4G ; pagination systématique.
- **Résilience réseau** : clés d'idempotence sur les écritures critiques pour éviter les doublons en cas de nouvelle tentative ; mode hors ligne partiel (§2.10, D17).
- **Hébergement (décision D19)** : hébergement cloud **évolutif, fiable et abordable** au lancement ; **portabilité des données** (formats et technologies standard) ; **sauvegardes restaurables**, testées régulièrement ; limitation de la **dépendance à un fournisseur** ; migration future possible vers une infrastructure au Sénégal ou dans une autre juridiction appropriée. Le choix du fournisseur et de la région reste ouvert (O12) et doit être compatible avec les validations juridiques du §5.1.
- **Internationalisation** : français au lancement, architecture extensible aux langues nationales (§2.11, D16).
- **Accessibilité / ergonomie** : mobile‑first, contrastes suffisants, libellés en français clair.
- **Observabilité** : logs structurés, Spring Actuator (santé, métriques), identifiant de corrélation par requête.
- **Maintenabilité** : monolithe modulaire, tests automatisés, conventions documentées dans `CLAUDE.md`.
- **Traçabilité** : journal d'audit des actions sensibles (annulation, remboursement, émission et utilisation d'avoir, remise appliquée par un employé et tentative refusée, modification de la limite de remise, ajustement de stock, changement de rôle, modification des paramètres sensibles, accès support, consentement IA, synchronisation hors ligne rejetée).

### 5.1 Protection des données personnelles et IA

**Localisation des données (décision D18)** : les données financières et commerciales des entreprises sont hébergées dans l'**infrastructure de FOOYRE** (base PostgreSQL du service). Seules les données **strictement nécessaires** à une analyse sont transmises à Anthropic, sans donnée personnelle de client pour les analyses ordinaires. Aucune analyse impliquant des données personnelles n'est prévue ; toute extension en ce sens exigerait une décision et une validation juridique spécifiques. Le transfert vers Anthropic et l'activation de l'IA en production restent **bloqués** jusqu'à l'obtention des validations juridiques et réglementaires ci‑dessous, notamment auprès de la CDP et pour les transferts internationaux.

**Mesures techniques prévues** (de la responsabilité de l'équipe de développement) :
- Isolation stricte des données par entreprise (`business_id`, RLS, tests anti‑IDOR), y compris pour les données conservées localement en mode hors ligne (§2.10).
- Minimisation des données transmises à l'IA : données **agrégées et pseudonymisées** en priorité ; **aucun nom ni numéro de téléphone de client** pour les analyses ordinaires.
- Aucun accès direct de l'IA à la base de données ; indicateurs calculés par le backend, jamais par le modèle ; réponses structurées validées par le backend.
- Quotas et plafonds de consommation par entreprise ; suivi des jetons et des coûts.
- Client IA simulé pour les tests (aucune donnée réelle envoyée en CI).
- Interrupteur global `AI_ENABLED`, et garde‑fou de production `AI_PRODUCTION_APPROVED` : en profil `prod`, l'IA ne peut pas être activée tant que ce drapeau n'est pas positionné après validation juridique.
- IA **désactivée par défaut** pour chaque entreprise ; activation **par entreprise** uniquement après **consentement explicite du propriétaire** (horodatage, auteur, version des conditions acceptées), **révocable à tout moment** (décision D15).
- Pas de données personnelles dans les logs ; mots de passe hachés ; HTTPS ; accès support journalisé (D12).
- Données hors ligne : minimales, cloisonnées par utilisateur et par entreprise, effacées à la déconnexion, durée de conservation limitée (D17).
- Sauvegardes chiffrées et restauration testée (D19).

**Obligations et validations juridiques restant à accomplir** (hors périmètre technique, à mener avec un·e juriste) :
- Analyse de conformité à la **loi n° 2008‑12** et aux exigences de la **CDP** (formalités éventuelles de déclaration ou d'autorisation des traitements).
- **Validation préalable du transfert de données** vers Anthropic ou tout autre fournisseur externe situé hors du Sénégal, même agrégées et pseudonymisées.
- Rédaction des conditions d'utilisation, de la politique de confidentialité et de l'information des clientes (y compris sur l'usage de l'IA et la sous‑traitance).
- Validation des durées de conservation (journaux d'audit, analyses IA, données clients, données hors ligne sur les appareils).
- Choix d'hébergement (fournisseur, région, sous‑traitance) compatible avec ces exigences (D19, O12).

**Aucune de ces validations n'a été réalisée à ce jour.** La fonctionnalité IA ne doit pas être activée en production, et aucune donnée ne doit être transmise à Anthropic depuis la production, avant ces validations.

---

## 6. Modèle économique (hypothèses non validées)

L'architecture d'abonnement est **flexible** (décision D10) : formules, fonctionnalités, quotas et limites sont des **données paramétrables** (table `subscription_plan` + dérogations par entreprise), modifiables sans redéploiement. Les prix seront validés commercialement à partir des retours du marché. **Aucun paiement réel d'abonnement** n'est développé à ce stade.

FOOYRE est envisagé en SaaS par abonnement mensuel, par exemple :

| Formule (hypothèse) | Cible | Limites indicatives |
|---|---|---|
| Découverte / gratuite | Test | 1 utilisateur, nb produits limité, IA très limitée |
| Essentiel | Entrepreneure seule | 2 utilisateurs, IA limitée |
| Pro | Boutique | 5 utilisateurs, rapports complets, catalogue digital |
| Business | Multi‑sites | Utilisateurs étendus, IA étendue |

Les tarifs, limites et noms sont **à valider**. Aucune intégration de paiement d'abonnement n'est développée ; seules les **limites** (quotas) sont modélisées techniquement.

---

## 7. Critères de succès du MVP (proposition)

1. Une entreprise peut gérer son catalogue, ses stocks, ses ventes (y compris partielles et à crédit) et ses dépenses.
2. Le tableau de bord affiche des indicateurs exacts, conformes au §3, vérifiés par des tests.
3. Aucune fuite de données entre entreprises (suite de tests d'isolation verte).
4. L'assistant IA produit une analyse fondée uniquement sur les données fournies et signale les insuffisances.

---

## 8. Décisions, hypothèses et points ouverts

### 8.1 Décisions validées (phase 0)

| # | Décision | Remplace | Détail |
|---|---|---|---|
| D1 | Valorisation au **coût moyen pondéré (CMP)** ; coût figé sur chaque ligne de vente ; corrections par mouvements compensatoires ; mise à jour atomique et protégée contre la concurrence | H1 | §3.1 |
| D2 | **JWT d'accès court + refresh token** en cookie `HttpOnly` (`Secure` en production), rotation, révocation, protection CSRF ; aucun jeton sensible dans `localStorage` | — | `architecture.md` ADR‑04 |
| D3 | Rôles OWNER / MANAGER / EMPLOYEE / PLATFORM_ADMIN et matrice du §4 ; autorisations vérifiées côté backend | H5 | §4 |
| D4 | TVA désactivée par défaut, activable par entreprise, saisie HT ou TTC configurable, taux initial 18 % configurable, calculs et arrondis centralisés et testés | H4 | §3.2 |
| D5 | Stock négatif interdit par défaut, autorisable explicitement par entreprise ; contrôle backend transactionnel sous verrou | H12 | §2.1 |
| D6 | Un utilisateur peut appartenir à plusieurs entreprises (`BusinessMembership`, rôle par appartenance, entreprise active vérifiée côté backend) ; isolation par `business_id` ; devise stockée explicitement (XOF initialement, une devise par entreprise) | H2, H3 | `architecture.md` §5 |
| D7 | PLATFORM_ADMIN sans accès par défaut ; accès support temporaire, justifié, autorisé, journalisé | H6 | §2.8 |
| D8 | Mesures techniques de protection des données et d'encadrement de l'IA | H7 | §5.1 |
| D9 | **Claude Opus 5.5** modèle IA par défaut (`ANTHROPIC_MODEL`), intégration isolée derrière une interface | — | `architecture.md` §7 |
| D10 | Abonnements flexibles, tarifs non figés, aucun paiement réel | — | §6 |
| D11 | Annulation d'une vente payée : restitution par **remboursement total ou partiel** et/ou **avoir client** ; historique conservé ; remboursement enregistré en trésorerie, avoir suivi séparément ; stock réintégré uniquement pour les produits retournés revendables | O1 | §2.2, §3 |
| D12 | Accès support uniquement après **approbation explicite du propriétaire**, durée limitée, périmètre défini, justification, journalisation, révocation possible | O3 | §2.8 |
| D13 | Annulations et corrections enregistrées dans la **période où elles interviennent** ; rapports historiques inchangés ; distinction ventes brutes / annulations‑ajustements / ventes nettes ajustées | O4 | §3 |
| D14 | Employé : consultation des ventes de l'entreprise sans coûts, marges ni données financières sensibles ; remises dans une limite configurée par le propriétaire ou le gestionnaire, contrôlées par le serveur et journalisées | O5 | §4 |
| D15 | IA désactivée par défaut ; activation par entreprise après consentement explicite du propriétaire, révocable à tout moment ; mise en production bloquée sans validations juridiques et réglementaires | O6 | §5.1 |
| D16 | Français comme langue initiale ; architecture extensible (wolof, pulaar, sérère, autres langues nationales) ; voix = évolution future à évaluer, sans promesse | H8 | §2.11 |
| D17 | Mode hors ligne **partiel** : consultation des dernières données synchronisées, mise en attente locale d'opérations autorisées, synchronisation idempotente avec détection et résolution des conflits, validation définitive uniquement par le serveur, protection et expiration des données locales | H9 | §2.10 |
| D18 | Données financières et commerciales hébergées dans l'infrastructure FOOYRE ; transmission à Anthropic limitée au strict nécessaire, sans donnée personnelle de client pour les analyses ordinaires ; transfert et IA en production bloqués jusqu'aux validations (CDP, transferts internationaux) | H10 | §5.1 |
| D19 | Hébergement cloud évolutif, fiable et abordable ; portabilité des données, sauvegardes restaurables, dépendance fournisseur limitée ; migration future possible vers le Sénégal ou une autre juridiction appropriée | H11 | §5 |
| D20 | Paramètres financiers et sensibles modifiables par le propriétaire seul ; le gestionnaire modifie les informations générales et consulte les paramètres sensibles sans les modifier | — | §4 |

### 8.2 Hypothèses encore ouvertes

Aucune : les hypothèses H1 à H12 ont toutes été remplacées par des décisions (colonne « Remplace » ci‑dessus) ou par les points ouverts ci‑dessous.

### 8.3 Points ouverts nécessitant un arbitrage humain

Les points O1, O3, O4, O5 et O6 ont été tranchés (D11 à D15). Restent ouverts :

| # | Question | Traitement provisoire documenté (non validé) | À trancher avant |
|---|---|---|---|
| O2 | Conformité fiscale des règles de calcul de la TVA par ligne, de la ventilation des remises et des arrondis (§3.2) ; traitement fiscal des annulations d'une période à l'autre | Règles du §3.2, **explicitement non validées**, à faire confirmer par un·e comptable | Phase 5 |
| O7 | Modalités des avoirs : durée de validité, remboursement ultérieur d'un avoir, utilisation par l'employé, obligation de restituer tout le montant payé (remboursement + avoir = montant payé) ou possibilité d'une retenue | Avoir sans date d'expiration ; remboursement + avoir = montant payé ; utilisation réservée au propriétaire et au gestionnaire | Phase 5 |
| O8 | Traitement comptable des marchandises non revendables lors d'une annulation (perte, présentation dans les rapports) | Aucun mouvement de stock ; coût non réintégré au CMV ; ligne tracée dans `sale_cancellation_line` ; à valider avec le·la comptable (lié à O2) | Phase 5 |
| O9 | Saisie antidatée (date de vente ou de dépense antérieure à la période en cours) et clôture des périodes : autorisée, limitée, réservée à certains rôles ? Elle est la seule source possible de modification d'une période passée (D13), y compris par synchronisation hors ligne | Date de vente libre mais journalisée ; aucune clôture de période | Phase 5 |
| O10 | Mode hors ligne : liste exacte des opérations autorisées, durées maximales de conservation et d'attente, justificatif provisoire remis au client, verrouillage local (code), politique de résolution des conflits (ex. prix modifié entre la saisie et la synchronisation), calendrier (avant ou après le lancement) | Candidats : création de vente avec paiement initial, encaissement sur une vente existante, création de client ; aucune autre opération | Phase dédiée au hors ligne |
| O11 | Forme de la limite de remise des employés : pourcentage, montant, par ligne ou par vente | Pourcentage maximal de la remise totale par rapport au montant brut de la vente ; défaut 0 (aucune remise) | Phase 5 |
| O12 | Fournisseur et région d'hébergement (latence vers Dakar, coût, conformité, réversibilité) | — | Phase de préparation à la production |
| O13 | Canal de réinitialisation du mot de passe et d'envoi des invitations (email, SMS, les deux) | Email | Phase 2 |
