# FlowForge — run guide

Updated 27 October 2026. Commands below are for macOS or Linux, using Bash or Zsh.
On this machine the project is at `/Users/bhuvan_sharma/Desktop/Folder/1`.

**Use Docker for the simplest startup.** Use development mode when editing the source and wanting live frontend updates.
Choose one mode at a time. FlowForge currently runs locally for one user, with no login; keep the supplied localhost bindings.

## 1. What you need

| Mode | Requirements |
|---|---|
| Full Docker stack | Docker with Compose v2, a running Docker engine, `openssl`, and internet access for the first build |
| Local development | The above, plus JDK 21+ and Node.js with npm. Node 26 is compatible with the installed frontend/test dependencies; Node 24.15+ or 22.22.2+ also satisfy their engine requirements. |

The Maven wrapper (`backend/mvnw`) is included; a separate Maven installation is unnecessary.
For Docker mode, Java, Node, and PostgreSQL are supplied by the containers.

Open Terminal and go to the project root:

```bash
cd /Users/bhuvan_sharma/Desktop/Folder/1
docker --version
docker compose version
docker info
```

If `docker info` cannot connect, start Docker Desktop and wait for its engine to be ready.
On another computer, replace the project path throughout this guide.

## 2. First-time configuration

**If `infra/.env` already exists, keep it and skip this initialization.** Existing databases need their original password;
stored credentials and webhook secrets need their original encryption key. Do not regenerate these on each launch.

This block creates the configuration only when it does not already exist:

```bash
if [ -e infra/.env ]; then
  echo "Keeping existing infra/.env. Continue with startup."
else
  (
    set -e
    umask 077
    cp infra/.env.example infra/.env
    sed -i.bak "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 16)/" infra/.env
    rm infra/.env.bak
    sed -i.bak "s|^FLOWFORGE_CREDENTIALS_KEYS=.*|FLOWFORGE_CREDENTIALS_KEYS=k1:$(openssl rand -base64 32)|" infra/.env
    rm infra/.env.bak
  )
fi
```

Keep a secure backup of `infra/.env` alongside your database backup. It is git-ignored and contains secrets.
If an existing file still has `POSTGRES_PASSWORD=change-me` or an empty encryption key, configure it before the
first database/credential use. For an existing database, follow the rotation/recovery instructions in
[Deployment](docs/DEPLOYMENT.md) instead of replacing working secrets.

Default ports in `infra/.env`:

| Service | Variable | Default |
|---|---|---|
| Frontend | `FRONTEND_PORT` | `5173` |
| Backend | `BACKEND_PORT` | `8080` |
| PostgreSQL | `POSTGRES_PORT` | `5432` |

## 3. Start everything in Docker

Run from the project root:

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app up -d --build --wait
```

The first build downloads images and dependencies. PostgreSQL starts first, the backend applies database migrations,
then the frontend starts. You do not need to create tables or run SQL manually.

Check service status:

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app ps
curl --fail http://127.0.0.1:8080/api/v1/health
```

The services should show as running and healthy. Open **[FlowForge](http://127.0.0.1:5173)**.
Use your configured ports if they differ from the defaults. The UI's **Backend connected** indicator confirms it can reach the API.

### Start the frontend container explicitly

If the backend and PostgreSQL are already running but the frontend is missing from `compose ps`, start it with:

```bash
cd /Users/bhuvan_sharma/Desktop/Folder/1
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app up -d --wait frontend
```

This also starts its backend and database dependencies if needed. The `app` profile enables the application services.
The frontend container serves the built React application through nginx and proxies `/api` to the backend container.
You do **not** need `npm run dev`, a local Node installation, or `VITE_PROXY_TARGET` for this mode.

For the first build, or after frontend source changes, use:

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app up -d --build --wait frontend
```

Verify that all three services, including `flowforge-frontend-1`, are healthy and that the frontend can reach the API:

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app ps -a
curl --fail http://127.0.0.1:5173/api/v1/health
```

Open **[http://127.0.0.1:5173](http://127.0.0.1:5173)**. The health request should return `"status":"UP"`.
Use the actual `FRONTEND_PORT` from `infra/.env` if you changed it.

If the frontend remains **Created** or startup reports **port already allocated**, a Vite development server may
already be using port 5173. Stop that server with `Ctrl+C` in its terminal, then rerun the frontend startup command.
Alternatively, choose an unused `FRONTEND_PORT` in `infra/.env` and rerun the command. Do not stop unrelated services.
After switching from Vite to Docker, hard-refresh the browser (`Cmd+Shift+R` on macOS, `Ctrl+Shift+R` on Windows/Linux).

To inspect frontend startup errors:

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app logs --tail=100 frontend
```

## 4. Run your first workflow

1. Click **New workflow**, enter a name such as `Hello FlowForge`, and create it.
2. Drag **Manual Trigger** onto the canvas, or double-click it in the node palette.
3. Select it. Set **Test input** to `{"name":"FlowForge"}`, then click **Apply**.
4. Add a **Logger** node. Set its message to `Hello {{ input.name }}`, then click **Apply**.
5. Connect the Manual Trigger output handle to the Logger input handle.
6. Click **Save** and wait for **All changes saved**. Click **Run**.
7. Confirm **Succeeded** and the message `Hello FlowForge` in the execution log.
8. Open **History** to inspect the saved execution, node inputs, outputs, and logs.

This example needs neither an API key nor outbound network access. Manual runs use the saved workflow;
save changes before running again. Webhook and Schedule workflows must be saved and **activated** to run automatically.

## 5. Stop, restart, and apply changes

Run these commands from the project root.

**Stop and remove the containers while keeping your workflows, credentials, and execution history:**

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app down
```

**Start again using existing images and data:**

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app up -d --wait
```

**After changing source code:**

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app up -d --build --wait
```

**After changing values in `infra/.env`:** use `up -d --wait` again so Compose recreates affected containers.
A plain `restart` does not apply changed environment values.

Do not add `-v` to `down` during normal use: it deletes the database volume. Prefer stopping after active runs finish;
long-running executions interrupted by shutdown may need to be run again.

## 6. Optional: run in development mode

Complete configuration in section 2 first. If the full Docker stack is running, stop it using section 5.
Do not run a container backend and a local backend against the same database at the same time.

### Start PostgreSQL

```bash
cd /Users/bhuvan_sharma/Desktop/Folder/1
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --wait postgres
```

### Terminal 1 — backend

Replace the two quoted placeholders with the existing values from `infra/.env`. Keep the quotes.
The key value includes its `k1:` prefix. This does not generate new secrets.

```bash
cd /Users/bhuvan_sharma/Desktop/Folder/1/backend
export DB_PASSWORD='paste the existing POSTGRES_PASSWORD value here'
export FLOWFORGE_CREDENTIALS_KEYS='paste the existing FLOWFORGE_CREDENTIALS_KEYS value here'
export DB_URL='jdbc:postgresql://127.0.0.1:5432/flowforge'
export DB_USERNAME='flowforge'
export SPRING_PROFILES_ACTIVE=dev
export SERVER_PORT=8080
./mvnw spring-boot:run
```

For custom database settings, match `DB_URL` to `POSTGRES_PORT` and `POSTGRES_DB`, and `DB_USERNAME` to `POSTGRES_USER`.
Local Spring Boot does **not** automatically load `infra/.env`; export other desired settings, such as
`FLOWFORGE_HTTP_ALLOWED_HOSTS`, in this terminal before starting it. Values typed into terminal commands may be retained
in shell history; use your preferred secret-loading mechanism if you want to avoid that.

### Terminal 2 — frontend

```bash
cd /Users/bhuvan_sharma/Desktop/Folder/1/frontend
npm ci
npm run dev
```

Run `npm ci` for initial setup or after dependency changes, with Vite stopped. It replaces `node_modules`, including
Vite's dependency cache; running it while a development server is active can cause `504 (Outdated Optimize Dep)` errors.
For subsequent launches, `npm run dev` is enough.

Open [the UI](http://127.0.0.1:5173). Keep both terminals running. Frontend edits update automatically;
restart the backend after backend code changes.

The Vite development server proxies `/api` to port `8080`. If the backend uses another port, supply the proxy target
in the shell when starting Vite, for example:

```bash
VITE_PROXY_TARGET=http://127.0.0.1:18080 npm run dev
```

Use `Ctrl+C` in each terminal to stop the applications. Stop PostgreSQL with:

```bash
cd /Users/bhuvan_sharma/Desktop/Folder/1
docker compose -f infra/docker-compose.yml --env-file infra/.env stop postgres
```

Swagger UI is available in development mode at [http://127.0.0.1:8080/swagger-ui.html](http://127.0.0.1:8080/swagger-ui.html).
It is disabled in the Docker stack's default `prod` profile.

## 7. Optional: HTTP requests and AI workflows

Outbound calls are disabled until you allow their destination hosts.

1. In `infra/.env`, set `FLOWFORGE_HTTP_ALLOWED_HOSTS` to a comma-separated list of the actual destination hostnames,
   without URL schemes or paths. For local development, export the same variable in the backend terminal.
2. Apply the configuration: recreate the Docker backend with `up -d --wait`, or restart the local backend.
3. In **Credentials**, create a credential and allow the destination hostname there as well. For an AI provider
   using bearer authentication, select **Bearer token** and enter the provider key.
4. Add an HTTP Request or AI Model node and select the stored credential. For AI, set the provider's compatible
   base URL, a model available to your account, and a prompt.
5. Save and run. The dashboard's **Try the AI sample** also creates a sample you can configure.

The server allowlist and credential allowlist must both permit the host. Keep provider keys in Credentials, not in prompts,
workflow JSON, or inline headers. See [the README](README.md#ai-model-node) for the sample's details.

## 8. Common startup problems

| Problem | What to do |
|---|---|
| Docker cannot connect | Start Docker Desktop / the Docker engine, then rerun `docker info`. |
| Port already in use | An existing app may already be running. For Docker, choose unused `FRONTEND_PORT`, `BACKEND_PORT`, and/or `POSTGRES_PORT` values in `infra/.env`, then run `up -d --wait`. Use the new UI URL. Do not stop unrelated services. |
| Browser shows backend disconnected | Check `compose ps` and backend logs. In development, verify the backend is running and `VITE_PROXY_TARGET` matches its port. |
| Console shows `504 (Outdated Optimize Dep)` for React or other packages | Stop Vite, run `npm run dev:fresh` from `frontend`, then hard-refresh the browser (`Cmd+Shift+R` on macOS, `Ctrl+Shift+R` on Windows/Linux). Preserve any custom `VITE_PROXY_TARGET` when restarting. |
| PostgreSQL password authentication fails | Restore the password used when the volume was created. Changing `POSTGRES_PASSWORD` in the file does not change an existing database password. |
| Credentials unavailable or cannot decrypt | Ensure the backend receives the original `FLOWFORGE_CREDENTIALS_KEYS` value. A new random key cannot decrypt existing secrets. |
| HTTP/AI host rejected | Check both host allowlists in section 7. Private-address restrictions are separate from host permission. |
| Save reports a conflict | Another tab may have saved first. Reload the workflow and reapply the change. |
| Schedule does not run | Save and activate it; inspect the trigger's timezone, next-run time, and errors. |
| Swagger returns 404 in Docker | Expected with the `prod` profile. Use local development for Swagger. |

Read backend logs from the project root:

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env --profile app logs --tail=100 backend
```

For continuous logs, add `-f`; `Ctrl+C` exits the log viewer without stopping the application.
More details: [Troubleshooting](docs/TROUBLESHOOTING.md), [backups and deployment](docs/DEPLOYMENT.md),
and [the latest review results](docs/POST_COMPLETION_REVIEW_2026-10-26.md).

## 9. Optional verification commands

These run tests; they are not required for every application launch.

```bash
# Backend unit and integration tests; Docker must be running.
(cd /Users/bhuvan_sharma/Desktop/Folder/1/backend && ./mvnw verify)

# Frontend checks.
(cd /Users/bhuvan_sharma/Desktop/Folder/1/frontend && npm ci && npm run typecheck && npm run lint && npm test && npm run build)
```

For the isolated end-to-end test stack, see [Deployment: running the test suites](docs/DEPLOYMENT.md#9-running-the-test-suites).
It is separate from the normal application and should not be used as your everyday startup command.
