# TrainTracker — Backend

API REST Spring Boot qui expose le suivi en temps réel des trains SNCF en combinant les données GTFS statiques (horaires) et GTFS-RT (retards temps réel).

## Stack

- Java 17
- Spring Boot 4.0.6
- Protobuf 3 — parsing du flux binaire GTFS-RT SNCF

## Sources de données

| Source | URL | Auth |
|---|---|---|
| GTFS statique (horaires + gares) | `eu.ftp.opendatasoft.com/sncf/plandata/...` | Aucune |
| GTFS-RT (retards temps réel) | `proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates` | Aucune |

## Lancer le projet

```bash
./mvnw spring-boot:run
```

L'API démarre sur `http://localhost:8080`.

Au premier démarrage, le chargement des données GTFS statiques prend quelques secondes.

## Endpoints

| Méthode | Endpoint | Description |
|---|---|---|
| `POST` | `/api/journeys` | Démarre le tracking d'un train |
| `GET` | `/api/journeys` | Liste tous les trajets suivis |
| `GET` | `/api/journeys/{id}` | Position + ETA + retard d'un trajet |
| `POST` | `/api/journeys/{id}/refresh` | Force une mise à jour immédiate |
| `DELETE` | `/api/journeys/{id}` | Arrête le tracking |

### Exemple — démarrer un tracking

```json
POST /api/journeys
{
  "trainNumber": "6201",
  "date": "2026-05-10"
}
```

### Réponse

```json
{
  "journeyId": "...",
  "trainNumber": "6201",
  "date": "2026-05-10",
  "status": "IN_PROGRESS",
  "delayMinutes": 5,
  "eta": "2026-05-10T18:42:00",
  "nextStop": { ... },
  "currentPosition": { "latitude": 48.85, "longitude": 2.35 },
  "allStops": [ ... ],
  "lastUpdated": "2026-05-10T17:30:00"
}
```

### Statuts possibles

| Statut | Signification |
|---|---|
| `SCHEDULED` | Le train n'est pas encore parti |
| `IN_PROGRESS` | En cours de route |
| `ARRIVED` | Arrivé à destination |
| `CANCELLED` | Annulé |
| `UNKNOWN` | Train introuvable dans le GTFS |

## Configuration

`src/main/resources/application.properties`

```properties
server.port=8080
gtfs.static.url=...      # URL du zip GTFS SNCF
gtfs.rt.url=...           # URL du flux GTFS-RT
tracking.refresh.rate-ms=120000  # Rafraîchissement auto toutes les 2 min
```

## Collection Postman

Un fichier `TrainTracker.postman_collection.json` est disponible à la racine du projet pour tester tous les endpoints.
