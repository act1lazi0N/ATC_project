# Transfer System

Internal money transfer system built as a monolithic backend service using **Spring Boot**, **PostgreSQL**, **Redis**, and **Flyway**.

The system supports JWT-based authentication, internal account transfers, idempotency control, audit logging, database migration, and Docker-based local development.

## Tech Stack

* **Backend:** Spring Boot
* **Database:** PostgreSQL
* **Cache / Idempotency:** Redis
* **Migration:** Flyway
* **Authentication:** JWT
* **Containerization:** Docker Compose
* **API Documentation:** Swagger UI / OpenAPI

## Quick Start

### 1. Copy environment template

```bash
cp .env.example .env
```

### 2. Generate JWT secret

Generate a secure JWT secret:

```bash
openssl rand -base64 32
```

Then copy the generated value into the `.env` file:

```env
JWT_SECRET=your_generated_secret
```

### 3. Start the full stack

This command starts PostgreSQL, Redis, and the Spring Boot application:

```bash
docker compose up -d
```

### 4. Check application logs

```bash
docker compose logs -f app
```

### 5. Open Swagger UI

After the application starts successfully, open:

```text
http://localhost:8080/swagger-ui/index.html
```

## Run Locally Without Dockerizing the App

If you want to run PostgreSQL and Redis with Docker, but run the Spring Boot application directly on your machine:

### 1. Start infrastructure only

```bash
docker compose up -d postgres redis
```

### 2. Run the application with Maven

```bash
JWT_SECRET=your_secret ./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
$env:JWT_SECRET="your_secret"
./mvnw spring-boot:run
```

## Flyway Migration

Flyway migrations run automatically when the application starts.

Migration files are located in:

```text
src/main/resources/db/migration/
```

| File                  | Description                                    |
| --------------------- | ---------------------------------------------- |
| `V1__init_schema.sql` | Creates database tables, indexes, and triggers |
| `V2__seed_data.sql`   | Inserts sample development data                |

> Do not edit migration files that have already been executed.
> Create a new migration file such as `V3__your_change.sql` instead.

## Demo Accounts

The seed migration creates disabled sample accounts for local data shape only. Do not use these as login credentials; create a user through `/api/v1/auth/register` or enable a local-only demo seed outside the production migration path.

| Email                   | Password      | Role  | Note                     |
| ----------------------- | ------------- | ----- | ------------------------ |
| `admin@transfer.local`  | Disabled | ADMIN | Sample admin account |
| `user_a@transfer.local` | Disabled | USER  | Sample user account |
| `user_b@transfer.local` | Disabled | USER  | Sample user account |
| `user_c@transfer.local` | Disabled | USER  | Sample user with frozen account |

## Useful Commands

### Stop containers but keep data

```bash
docker compose stop
```

### Stop containers and remove all data

```bash
docker compose down -v
```

### Restart the application container

```bash
docker compose restart app
```

### View PostgreSQL logs

```bash
docker compose logs -f postgres
```

### View Redis logs

```bash
docker compose logs -f redis
```

## Project Structure

```text
src/main/java
└── com/actilazion/aries_transaction
    ├── config
    ├── controller
    ├── dto
    ├── entity
    ├── exception
    ├── repository
    └── service

src/main/resources
├── application.yaml
└── db/migration
    ├── V1__init_schema.sql
    └── V2__seed_data.sql
```

## Main Features

* User registration and login with JWT authentication
* Stateless Spring Security configuration
* Internal account-to-account money transfer
* PostgreSQL persistence with JPA
* Redis-based idempotency control
* Flyway database migration
* Transaction audit logging
* Account balance validation
* Transaction history API
* Swagger UI for API testing

## Notes

* The application requires a valid `JWT_SECRET`.
* PostgreSQL and Redis must be running before starting the application locally.
* Seed data is intended for development and testing only.
* For production deployment, replace demo credentials, use a secure JWT secret, and review database/security configuration.
