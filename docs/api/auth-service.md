# auth-service API

Registration, login, token refresh and logout. Issues the JWT access token that every other service verifies.

- Gateway: `http://localhost:9780/api/v1/auth/...`
- Direct: `http://localhost:8081/api/v1/auth/...`
- Authentication: none (public endpoints). Rate limited at the gateway: 10 requests/minute per client address when Redis
  limiting is on.

```bash
export BASE=http://localhost:9780
```

Token response (used by register, login and refresh):

| Field | Type | Description |
| --- | --- | --- |
| `accessToken` | string | signed JWT, valid for `expiresIn` seconds; send as `Authorization: Bearer ...` |
| `refreshToken` | string | opaque single-use token (valid 7 days); rotated on every refresh |
| `tokenType` | string | always `Bearer` |
| `expiresIn` | number | access token lifetime in seconds (`900`) |

The access token carries `sub` (user id), `username`, `role` (`CUSTOMER`, `ADMIN`, `OPERATIONS` or `COMPLIANCE`), `iat`, `exp`.

---

## 1. Register

Creates the login identity and publishes a `UserRegistered` event. The customer profile is created asynchronously by the
customer-service, so `GET /api/v1/customers/me` may return `404 PROFILE_NOT_FOUND` for a moment after registering.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:9780/api/v1/auth/register` |
| Direct URL | `POST http://localhost:8081/api/v1/auth/register` |
| Auth | none |

**Headers**

| Header | Value | Required |
| --- | --- | --- |
| `Content-Type` | `application/json` | yes |

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `username` | string | required, 3-50 chars, only letters, digits, `.`, `_`, `-`; stored lower-case |
| `email` | string | required, valid e-mail; stored lower-case |
| `password` | string | required, 8-72 chars |
| `fullName` | string | required, max 120 chars |
| `phone` | string | required, `+` optional then 10-15 digits |
| `dateOfBirth` | string | required, `YYYY-MM-DD`, must be in the past |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "alice",
    "email": "alice@example.com",
    "password": "Passw0rd!23",
    "fullName": "Alice Example",
    "phone": "9876543210",
    "dateOfBirth": "1990-01-01"
  }'
```

**Success: `201 Created`**

```
HTTP/1.1 201
Content-Type: application/json
X-Request-Id: 8c5f2f0e-3b8d-4a49-9d57-0b6f3c1e5a77
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3YzllNjY3OS03NDI1LTQwZGUtOTQ0Yi1lMDdmYzFmOTBhZTciLCJ1c2VybmFtZSI6ImFsaWNlIiwicm9sZSI6IkNVU1RPTUVSIiwiaWF0IjoxNzY0MDAwMDAwLCJleHAiOjE3NjQwMDA5MDB9.4Qd3kq1m0nV0nq0f1b0aYk6vYvQ8o2c8aX6u6wq0Y1s",
  "refreshToken": "x3Qm9Wc7nT0vJb2YpH6rLdKf1sA8uE4oZgN5iV7tRXk",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

**Errors**

`409 Conflict`: username already used

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Username is already taken",
  "instance": "/api/v1/auth/register",
  "code": "USERNAME_TAKEN"
}
```

`409 Conflict`: e-mail already used (`"detail": "Email is already registered"`, `"code": "EMAIL_TAKEN"`).

`400 Bad Request`: validation

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "errors": {
    "username": "size must be between 3 and 50",
    "email": "must be a well-formed email address",
    "password": "size must be between 8 and 72",
    "phone": "must be 10-15 digits"
  }
}
```

---

## 2. Login

| | |
| --- | --- |
| Method / URL | `POST http://localhost:9780/api/v1/auth/login` |
| Direct URL | `POST http://localhost:8081/api/v1/auth/login` |
| Auth | none |

**Headers**: `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `username` | string | required (case-insensitive) |
| `password` | string | required |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Passw0rd!23"}'
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 1f0c3e8a-52a9-4d1e-8e3a-7a9d2b6c4e10
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3YzllNjY3OS03NDI1LTQwZGUtOTQ0Yi1lMDdmYzFmOTBhZTciLCJ1c2VybmFtZSI6ImFsaWNlIiwicm9sZSI6IkNVU1RPTUVSIiwiaWF0IjoxNzY0MDAwMTIwLCJleHAiOjE3NjQwMDEwMjB9.hX2YcT5n1pQ0w8mYb3sA7uZ9kLr4eVd6oJt1gN8xP2c",
  "refreshToken": "P7vLd2Xk9sQ4mTn0aYc6HbWe3rUj8oZf1gKi5NtVxEA",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

**Errors**

`401 Unauthorized`: wrong password, unknown user or disabled user. The same message is returned for all three so
usernames cannot be enumerated.

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid credentials",
  "instance": "/api/v1/auth/login",
  "code": "UNAUTHORIZED"
}
```

`400 Bad Request`: blank `username` or `password` (`code: VALIDATION_FAILED`, `errors: {"username": "must not be blank"}`).

Every attempt (success or failure) is written to the audit log of this service (`LOGIN_SUCCEEDED` / `LOGIN_FAILED`).

---

## 3. Refresh tokens

Exchanges a refresh token for a new access token **and a new refresh token**. The presented token is revoked.
Presenting an already-used (revoked) refresh token is treated as theft: **all** sessions of that user are revoked.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:9780/api/v1/auth/refresh` |
| Direct URL | `POST http://localhost:8081/api/v1/auth/refresh` |
| Auth | none |

**Headers**: `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `refreshToken` | string | required |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/auth/refresh" \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"P7vLd2Xk9sQ4mTn0aYc6HbWe3rUj8oZf1gKi5NtVxEA"}'
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 6a1d4c2b-9e0f-4f7a-b3d1-2c5e8f9a0b34
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3YzllNjY3OS03NDI1LTQwZGUtOTQ0Yi1lMDdmYzFmOTBhZTciLCJ1c2VybmFtZSI6ImFsaWNlIiwicm9sZSI6IkNVU1RPTUVSIiwiaWF0IjoxNzY0MDAwNTAwLCJleHAiOjE3NjQwMDEzOTl9.mB7sT0qW3xYc9eRk2uZ5vN1pLd8oAj4hFg6iKt0nXyU",
  "refreshToken": "Gc4Nw8Ys1Tq6MxEa0JbZ3rHdLk7uVf2oPi9tAnXeSjQ",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

**Errors** (all `401 Unauthorized`, `code: UNAUTHORIZED`)

| `detail` | Cause |
| --- | --- |
| `Invalid refresh token` | unknown token, or its user is disabled |
| `Refresh token expired` | older than 7 days |
| `Refresh token reuse detected, all sessions revoked` | the token was already used; every active session of the user is revoked |

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Refresh token reuse detected, all sessions revoked",
  "instance": "/api/v1/auth/refresh",
  "code": "UNAUTHORIZED"
}
```

---

## 4. Logout

Revokes the given refresh token. The access token stays valid until it expires (15 minutes).

| | |
| --- | --- |
| Method / URL | `POST http://localhost:9780/api/v1/auth/logout` |
| Direct URL | `POST http://localhost:8081/api/v1/auth/logout` |
| Auth | none |

**Headers**: `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `refreshToken` | string | required |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/auth/logout" \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"Gc4Nw8Ys1Tq6MxEa0JbZ3rHdLk7uVf2oPi9tAnXeSjQ"}'
```

**Success: `204 No Content`**

```
HTTP/1.1 204
X-Request-Id: 0b9e7d5c-1a3f-4c28-a6d4-9f2e1b8c7a65
```

(no body). An unknown token is also answered with `204`; logout never reveals whether a token existed.

**Errors**: `400` with `VALIDATION_FAILED` if `refreshToken` is blank.

---

## 5. Create a staff user (admin)

Creates a login with a staff role. Only `ADMIN` may do this. No customer profile is created for staff. The action is
written to the audit log as `STAFF_CREATED`.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:9780/api/v1/admin/staff` |
| Direct URL | `POST http://localhost:8081/api/v1/admin/staff` |
| Auth | `Authorization: Bearer <ADMIN access token>` |

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `username` | string | required, 3-50 chars, letters, digits, `.`, `_`, `-` |
| `email` | string | required, valid email |
| `password` | string | required, 8-72 chars |
| `role` | string | required, `ADMIN`, `OPERATIONS` or `COMPLIANCE` (`CUSTOMER` is rejected) |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/admin/staff" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username":"ops.priya","email":"priya@fincore.local","password":"Passw0rd!23","role":"OPERATIONS"}'
```

**Success: `201 Created`**

```
HTTP/1.1 201
Content-Type: application/json
X-Request-Id: 1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f
```

```json
{
  "id": "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9",
  "username": "ops.priya",
  "email": "priya@fincore.local",
  "role": "OPERATIONS",
  "enabled": true
}
```

**Errors**: `400 VALIDATION_FAILED`; `400 INVALID_ROLE` (`"detail": "Role must be a staff role"`);
`409 USERNAME_TAKEN` / `EMAIL_TAKEN`; `403 FORBIDDEN` for any role except `ADMIN`; `401` without a token.

The new user logs in with the normal `POST /api/v1/auth/login` and receives a token whose `role` is the staff role.

## 6. List staff users (admin)

| | |
| --- | --- |
| Method / URL | `GET http://localhost:9780/api/v1/admin/staff` |
| Direct URL | `GET http://localhost:8081/api/v1/admin/staff` |
| Auth | `Authorization: Bearer <ADMIN access token>` |

```bash
curl -i "$BASE/api/v1/admin/staff" -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`** (ordered by username; every non-customer user)

```json
[
  {"id": "2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d", "username": "admin", "email": "admin@fincore.local", "role": "ADMIN", "enabled": true},
  {"id": "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9", "username": "ops.priya", "email": "priya@fincore.local", "role": "OPERATIONS", "enabled": true}
]
```

**Errors**: `403 FORBIDDEN` for any role except `ADMIN`.

---

## Other admin endpoints on this service

Reachable through the direct port only, see [admin-operations.md](admin-operations.md):
`GET http://localhost:8081/api/v1/admin/audit`, `GET http://localhost:8081/api/v1/admin/outbox/summary`.
