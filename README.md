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

Lobbys **jetables** créés et supprimés sur Pterodactyl par le moteur d'**EterVelocityLib** (famille `eterlobby`,
voir son README : modèle, règles, sécurité), ajoutés à Velocity à chaud : plus besoin de les mettre dans `try`.

- Règles dans `servers:` (`display-name: 'Lobby {n}'`, `max-lifetime-hours: 0` : un lobby n'expire pas).
- Un joueur d'un lobby supprimé va sur un autre lobby (`Lobbies#best`).
- À la suppression, ses lignes de `eter_servers` et `eterhub_lobbies` sont retirées.
- `/eterlobby list | status | create | drain <lobby>` (`etervelocitylobby.admin`).

## Technique

- Dépend d'**EterVelocityLib** (`@Dependency`) : config, langues (textes communs `command.*`, `orchestrator.*`),
  préfixe et palette (réglés dans le `config.yml` d'EterVelocityLib), orchestrateur.
- La version est aussi écrite dans `@Plugin` (`EterVelocityLobby`) : à garder identique à `gradle.properties`.
- Compilation : `gradlew build` → `build/libs/EterVelocityLobby-<version>.jar`, copié dans `<eterPluginsDir>/velocity`.
