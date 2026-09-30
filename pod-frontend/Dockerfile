# syntax=docker/dockerfile:1
# The web app, built with output: "standalone" (next.config.ts) and served by its own server.js.
# NEXT_PUBLIC_* are compiled into the browser code, so they're build arguments: the public
# addresses of pod-backend, pod-webhooks and pod-connector as the browser reaches them.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_BACKEND_URL=http://localhost:8083
ARG NEXT_PUBLIC_WEBHOOKS_URL=http://localhost:8080
ARG NEXT_PUBLIC_CONNECTOR_URL=http://localhost:8084
ENV NEXT_PUBLIC_BACKEND_URL=$NEXT_PUBLIC_BACKEND_URL \
    NEXT_PUBLIC_WEBHOOKS_URL=$NEXT_PUBLIC_WEBHOOKS_URL \
    NEXT_PUBLIC_CONNECTOR_URL=$NEXT_PUBLIC_CONNECTOR_URL \
    NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1
COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
USER node
EXPOSE 3000
CMD ["node", "server.js"]
