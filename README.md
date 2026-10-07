# EterVelocityLobby

Les lobbys du réseau, côté **proxy Velocity** (4.2+, Java 25). Document développeur, à tenir à jour avec le code.
Le contenu des lobbys (protection, menus, double saut...) est dans **EterHub**, sur les serveurs Paper des lobbys.

## Ce qu'il fait

- **Les lobbys = la liste `try` de `velocity.toml`** : rien à configurer en double. Ajouter un lobby, c'est l'ajouter
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

## Technique

- **Indépendant d'EterLib** (qui est pour Paper) : `core/Config`, `core/Lang`, `core/YamlFiles` et `helper/Messages`
  sont la même base que dans EterTab-Velocity (config + langues + palette lues avec SnakeYAML fourni par Velocity).
  Si un troisième plugin proxy arrive, les sortir dans une petite bibliothèque commune pour le proxy.
- Préfixe et palette dans `config.yml`, à garder identiques à EterLib (le proxy ne lit pas sa config).
- La version est aussi écrite dans `@Plugin` (`EterVelocityLobby`) : à garder identique à `gradle.properties`.
- Compilation : `gradlew build` → `build/libs/EterVelocityLobby-<version>.jar`, copié dans `<eterPluginsDir>/velocity`.
