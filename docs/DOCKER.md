# Docker commands

Run everything from the repository root (`fincore-backend/`). Docker must be running.

Services in `docker-compose.yml`: `postgres`, `redis`, `jaeger`, `kafka` (infrastructure) and `api-gateway`,
`auth-service`, `customer-service`, `banking-core`, `payment-service`, `notification-service`, `scheduler-service`.

## Start

```bash
scripts/up.sh                              # stops old containers, frees the backend ports, builds and starts everything
```

`scripts/up.sh` first runs `scripts/free-ports.sh`, which stops whatever is listening on the ports the backend needs
(9780, 9781, 8081 to 8086, 5432, 6379, 9092, 16686, 4318): a local Redis or Postgres, or a service left running from
your IDE. Preview it with `scripts/free-ports.sh --dry-run`; keep a port out of it with
`FREE_PORTS_EXCLUDE="5432 6379" scripts/up.sh`. A Homebrew service (Redis, Postgres) is stopped with
`brew services stop`, so it does not restart itself.

```bash
docker compose up --build                  # build images and start everything (foreground, Ctrl+C stops)
```

```bash
docker compose up -d --build               # same, in the background
```

```bash
docker compose up -d postgres kafka redis # infrastructure only, run services from your IDE
```

```bash
docker compose up -d --build banking-core  # one service (its dependencies start too)
```

## Status and logs

```bash
docker compose ps                          # state and health of every container
```

```bash
docker compose logs -f                     # follow all logs
```

```bash
docker compose logs -f banking-core        # follow one service
```

```bash
docker compose logs --tail=100 api-gateway # last 100 lines
```

```bash
curl http://localhost:9781/actuator/health # gateway health
```

## Stop and restart

```bash
docker compose stop                        # stop containers, keep them and the data
```

```bash
docker compose start                       # start stopped containers again
```

```bash
docker compose restart auth-service        # restart one service
```

```bash
docker compose down                        # stop and remove containers and network (data volume kept)
```

```bash
docker compose down -v                     # also delete the database volume (wipes all data)
```

## Rebuild after code changes

```bash
docker compose build                       # rebuild all images
```

```bash
docker compose build --no-cache banking-core   # rebuild one image from scratch
```

```bash
docker compose up -d --build --no-deps banking-core   # rebuild and replace one running service
```

## Build one image by hand

The single `Dockerfile` is parameterised by module; `MODULE` is the Maven module name.

```bash
docker build --build-arg MODULE=banking-core -t fincore/banking-core .
```

```bash
docker run --rm -p 8083:8083 -e DB_URL=jdbc:postgresql://host.docker.internal:5432/fincore_core fincore/banking-core
```

## Shell and database access

```bash
docker compose exec postgres psql -U fincore -l                    # list databases
```

```bash
docker compose exec postgres psql -U fincore -d fincore_core       # open a SQL prompt
```

```bash
docker compose exec redis redis-cli ping                           # expect PONG
```

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list
```

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic fincore.transfer-events --from-beginning
```

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic fincore.transfer-events.DLT --from-beginning
```

## Ports

| Port | What |
| --- | --- |
| 9780 | api-gateway (the entry point) |
| 8081 to 8086 | auth, customer, banking-core, payment, notification, scheduler |
| 5432 | PostgreSQL |
| 6379 | Redis |
| 9092 | Kafka (host); `kafka:29092` inside the compose network |
| 16686 | Jaeger UI |
| 4318 | Jaeger OTLP/HTTP receiver |

## Clean up

```bash
docker compose down -v --remove-orphans    # containers, network, volumes
```

```bash
docker image prune -f                      # remove dangling images left by rebuilds
```

```bash
docker system df                           # see what Docker is using
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Cannot connect to the Docker daemon` | start Docker Desktop |
| `Ports are not available ... address already in use` | run `scripts/free-ports.sh` (or `scripts/up.sh`), or move that port: `REDIS_HOST_PORT=6380 docker compose up -d` |
| `port is already allocated` | stop whatever uses the port (`lsof -i :9780`) or change the mapping in `docker-compose.yml` |
| a service keeps restarting | `docker compose logs --tail=200 <service>`; usually the database or Kafka was not ready yet |
| stale database schema after changing migrations | `docker compose down -v`, then `up --build` |
| build fails inside the image | read the Maven error in the build output; the README warns the code was never compiled |
