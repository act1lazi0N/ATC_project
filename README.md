# Transfer System

Hệ thống chuyển tiền nội bộ — Monolithic, Spring Boot 4.x, PostgreSQL, Redis.

## Quick Start

```bash
# 1. Copy env template
cp .env.example .env

# 2. Generate JWT secret và điền vào .env
openssl rand -base64 32

# 3. Chạy toàn bộ stack (PostgreSQL + Redis + App)
docker compose up -d

# 4. Kiểm tra log
docker compose logs -f app

# 5. Swagger UI
open http://localhost:8080/swagger-ui/index.html
```

## Chạy local (không dùng Docker cho app)

```bash
# Chỉ chạy infrastructure
docker compose up -d postgres redis

# Chạy app với Maven
JWT_SECRET=your_secret ./mvnw spring-boot:run
```

## Flyway Migration

Migration tự động chạy khi app start.
Files nằm ở `src/main/resources/db/migration/`:

| File | Nội dung |
|------|----------|
| `V1__init_schema.sql` | Tạo toàn bộ bảng, index, trigger |
| `V2__seed_data.sql`   | Dữ liệu mẫu cho dev |

**Lưu ý:** Không sửa file migration đã chạy — tạo file V3__ mới.

## Demo accounts (sau khi chạy V2 seed)

| Email | Password | Role |
|-------|----------|------|
| admin@transfer.local | password123 | ADMIN |
| user_a@transfer.local | password123 | USER |
| user_b@transfer.local | password123 | USER |
| user_c@transfer.local | password123 | USER (frozen account) |

## Dừng và xóa data

```bash
# Dừng nhưng giữ data
docker compose stop

# Dừng và xóa container + volume (reset hoàn toàn)
docker compose down -v
```
