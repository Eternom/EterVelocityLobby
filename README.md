# EterVelocityLobby

Les lobbys du réseau, côté **proxy Velocity** (4.2+, Java 25). Document développeur, à tenir à jour avec le code.
Le contenu des lobbys (protection, menus, double saut...) est dans **EterHub**, sur les serveurs Paper des lobbys.

## Ce qu'il fait

- **Les lobbys = la liste `try` de `velocity.toml`**, plus ceux de l'orchestrateur : rien à configurer en double. Ajouter un lobby, c'est l'ajouter
  à `try` (et y installer EterHub). `Lobbies` interroge chacun toutes les 5 s (ping, 3 s max).
- **Arrivée sur le réseau** (`PlayerChooseInitialServerEvent`) : le lobby qui répond et a le moins de joueurs.
- **`/lobby`** (`/hub`, `/l`), pour tous, depuis n'importe quel serveur (même sans plugin dessus) : le meilleur lobby ;
  « déjà au lobby » si on y est.
- **Expulsé d'un serveur** (arrêt, plantage, kick) : renvoyé sur un autre lobby avec la raison (`lobby.kicked`), au
  lieu d'être déconnecté. Un `/server` raté alors qu'on est déjà ailleurs reste géré par Velocity (on ne bouge pas).
  Sans lobby joignable : comportement normal de Velocity.
- **Arrivée et départ du réseau** (`announce`) : un seul message à tous, dans la langue de chacun, pas à chaque
  changement de serveur. Les messages de Minecraft sur les serveurs Paper sont coupés par EterLib
  (`vanilla-join-quit-messages: false`). Un départ n'est annoncé que si l'arrivée l'a été.

## Orchestrateur (facultatif, `orchestrator.enabled`)

Lobbys **jetables** créés et supprimés sur Pterodactyl (`module/orchestrator`), ajoutés à Velocity à chaud
(`registerServer`) : plus besoin de les mettre dans `try`.

- **Modèle** (`template/`) : `lobby.zip` ou `lobby.tar.gz` (format des archives du panel) (monde sans données de joueurs, fichiers du serveur, plugins tiers et leur
  config) + `EterLib-config.yml` (config d'EterLib avec `%server%` et `%display%`). Les plugins Eter sont la
  **dernière release GitHub** de chacun (`orchestrator.plugins`, jars gardés dans `cache/`).
- **Création** : ligne `CREATING` en base **avant** le panel (une création interrompue reste retrouvable) → serveur
  créé par **déploiement automatique** (`location-id` : le panel choisit le nœud et un port libre, dans
  `port-range` s'il est réglé ; œuf, propriétaire dédié, identifiant externe `eterlobby:<nom>`) → attente de
  l'installation → envoi du zip, décompression → envoi des jars dans `plugins/` → config d'EterLib → démarrage →
  ajout à Velocity → `ACTIVE` dès qu'il répond. Échec : suppression (sûre).
- **Règles** (toutes les 30 s, un seul fil) : au moins `minimum` lobbys **à jour** ; un de plus quand ils sont remplis
  à `scale-up-at` (au plus `maximum`) ; une ancienne version (empreinte du zip + tag de chaque plugin, relue toutes les
  10 min) est vidée dès que les lobbys à jour suffisent ; un lobby en trop vide depuis `idle-minutes`, ou qui ne répond
  plus depuis 5 min, est vidé. **Vidé** (`DRAINING`) : plus de nouveaux joueurs, supprimé une fois vide ou après
  `drain-timeout-minutes` (joueurs envoyés sur un autre lobby).
- **Sécurité** (le panel héberge aussi les serveurs des clients) :
  - table `eterlobby_servers` = seule source de vérité ; un serveur n'est supprimé que s'il y figure, appartient à
    `owner-user-id` ET porte l'identifiant externe `eterlobby:<nom>` ; sinon erreur dans la console, rien n'est touché ;
  - serveurs « eterlobby: » du panel absents de la table : seulement signalés, jamais supprimés ;
  - `dry-run` (par défaut) : écrit ce qu'il ferait, sans rien faire ;
  - panel en `https://` obligatoire (clés et accès à la base envoyés) ; clés et mots de passe jamais écrits dans la
    console (erreurs YAML sans la ligne fautive, pas de trace complète).
- **Base** : accès lus dans `EterLib-config.yml` ; pilote MariaDB téléchargé au premier démarrage dans `libs/` et
  ajouté au proxy (`addToClasspath`) : rien d'embarqué dans le jar. À la suppression d'un lobby, ses lignes de
  `eter_servers` et `eterhub_lobbies` sont retirées aussi.
- `/eterlobby list | status | create | drain <lobby>` (`etervelocitylobby.admin`).

## Technique

- **Indépendant d'EterLib** (qui est pour Paper) : `core/Config`, `core/Lang`, `core/YamlFiles` et `helper/Messages`
  sont la même base que dans EterTab-Velocity (config + langues + palette lues avec SnakeYAML fourni par Velocity).
  Si un troisième plugin proxy arrive, les sortir dans une petite bibliothèque commune pour le proxy.
- Préfixe et palette dans `config.yml`, à garder identiques à EterLib (le proxy ne lit pas sa config).
- La version est aussi écrite dans `@Plugin` (`EterVelocityLobby`) : à garder identique à `gradle.properties`.
- Compilation : `gradlew build` → `build/libs/EterVelocityLobby-<version>.jar`, copié dans `<eterPluginsDir>/velocity`.
