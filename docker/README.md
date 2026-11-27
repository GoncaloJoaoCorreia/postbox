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
