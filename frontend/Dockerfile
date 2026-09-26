# Build context is the REPOSITORY ROOT, not ./frontend — the console renders the
# markdown in /docs, so both trees have to be in the image. docker-compose.yml
# sets `context: .` with `dockerfile: frontend/Dockerfile`; a root .dockerignore
# keeps that context small.

# ---- Build stage ----
FROM node:20-alpine AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# /build/src/data/../../../docs resolves to /docs, which is the same relative
# path src/data/docs.js uses in a checkout.
COPY docs/ /docs/
RUN npm run build

# ---- Runtime stage: static files behind nginx, /api proxied to the gateway ----
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
