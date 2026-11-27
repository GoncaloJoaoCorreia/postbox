# docker

`Dockerfile` builds the single-container image: a Node stage builds the SPA, a Gradle
stage packages the `postbox.jar` fat jar with the SPA embedded as static resources, and
a slim JRE stage runs it (API + SPA on one port, SQLite on the `/data` volume, all
config via env vars). Build from the repo root:

```sh
docker build -f docker/Dockerfile -t postbox .
```

The image is published to GHCR as `ghcr.io/<owner>/postbox` on `v*` tags by
`.github/workflows/docker-publish.yml`.

To run the published image without typing the `docker run` flags, use the
bundled `docker-compose.yml` (named `postbox-data` volume, env section with a
required `JWT_SECRET` — see the quick start in the root `README.md`):

```sh
cd docker
echo "JWT_SECRET=$(openssl rand -hex 32)" > .env
docker compose up -d
```
