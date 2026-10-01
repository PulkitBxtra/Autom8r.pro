# Contributing to Autom8r

Thanks for your interest in Autom8r. Bug reports, ideas, docs fixes and code are all welcome.

## Before you start

- **Bugs:** open an issue with what you did, what you expected and what happened instead. Include logs if you have them, with any secrets removed.
- **Features:** open an issue first so we can agree on the approach before you write code. Small fixes can go straight to a pull request.
- **Security problems:** please don't open a public issue. Report them privately through GitHub's [security advisories](https://github.com/PulkitBxtra/Autom8r.pro/security/advisories/new).

## Setting up

Follow [Local development](README.md#-local-development) in the README. You'll have Postgres and Kafka running in Docker and the pods running from source.

## Making a change

1. Fork the repository and create a branch from `main`, for example `fix/slack-retry` or `feat/gmail-trigger`.
2. Keep each pull request to one change. Small, focused pull requests get reviewed faster.
3. Add or update tests for what you changed, and make sure they pass:
   ```bash
   source ~/.autom8r/dev.env
   cd pod-<name> && ./mvnw test
   cd pod-frontend && npm run lint && npm run build
   ```
4. Open the pull request and say **what** changed and **why**. Link the issue it closes.

## Conventions

**Code**
- Match the style of the code around your change: naming, structure and how much it comments. Comments explain *why*, not *what*.
- **Java:** use Java 21 and Spring Boot 4. Every pod is its own Maven project, so build and test it from its own folder.
- **Database:** pod-backend owns the schema. Make every change as a new Flyway migration in `pod-backend/src/main/resources/db/migration/` (`V<next number>__what_it_does.sql`). Never edit a migration that has already been released. The other pods only check the schema (`ddl-auto=validate`).
- **Frontend:** the frontend uses Next.js 16, where the middleware file is `proxy.ts`. Its APIs differ from older versions, so check the docs that come with the installed version (`node_modules/next/dist/docs`). Use the colour tokens in `app/globals.css` (black, mineshaft, lemon) instead of new hard-coded colours.

**New apps**
- A new app needs a catalog entry in `pod-backend/src/main/resources/catalog/apps.json`.
- Its action handler goes in `pod-processor`, under `service/handlers/`.
- If it signs in with OAuth or has triggers, its provider goes in `pod-connector`.

**Secrets and commits**
- Never commit secrets: no `.env` files, keys, tokens or certificates. Settings come from environment variables. Add new ones to `deploy/.env.example` with a comment, and to `deploy/docker-compose.yml`.
- Commit messages: one plain sentence in lower case that says what the change does, for example `retry Notion's token exchange once when it times out`.

## Licensing of contributions

Autom8r is released under the [PolyForm Noncommercial License 1.0.0](LICENSE), and the owner also offers commercial licenses. By submitting a contribution, you agree to two things:

- you have the right to submit it, and
- it may be distributed under the project's license, and the project owner may also license it to others under commercial terms.

If you can't agree to this, say so in your pull request before it is merged.

## Code of conduct

Be kind and constructive, and assume good intent. Harassment or personal attacks of any kind aren't tolerated. Issues, comments or pull requests that cross that line may be removed.
