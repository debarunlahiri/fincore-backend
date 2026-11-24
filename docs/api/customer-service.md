# customer-service API

Customer profile and simulated KYC. The profile is created automatically from the `UserRegistered` event published by
the auth-service; there is no "create profile" endpoint.

- Gateway: `http://localhost:8080/api/v1/customers/...`
- Direct: `http://localhost:8082/api/v1/customers/...`
- Authentication: `Authorization: Bearer <customer access token>`. Role `CUSTOMER` only (a staff token gets `403`).

```bash
export BASE=http://localhost:8080
export TOKEN="<accessToken>"
```

Customer response (used by all three endpoints):

| Field | Type | Description |
| --- | --- | --- |
| `id` | uuid | customer id |
| `fullName` | string | |
| `phone` | string | |
| `dateOfBirth` | string | `YYYY-MM-DD` |
| `kycStatus` | string | `PENDING`, `VERIFIED` or `REJECTED` |

Errors common to all endpoints:

`401 Unauthorized`: missing, malformed or expired token (empty body).

`403 Forbidden`: token belongs to an admin

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "You are not allowed to perform this action",
  "instance": "/api/v1/customers/me",
  "code": "FORBIDDEN"
}
```

`404 Not Found`: the profile has not been created yet (registration is asynchronous), retry shortly

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Customer profile not found (it is created asynchronously after registration; retry shortly)",
  "instance": "/api/v1/customers/me",
  "code": "PROFILE_NOT_FOUND"
}
```

---

## 1. Get my profile

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/customers/me` |
| Direct URL | `GET http://localhost:8082/api/v1/customers/me` |

**Headers**

| Header | Value | Required |
| --- | --- | --- |
| `Authorization` | `Bearer <accessToken>` | yes |

**curl**

```bash
curl -i "$BASE/api/v1/customers/me" -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 4e2a7c90-3d1b-4b6a-8f5e-0a9c8d7b6e51
```

```json
{
  "id": "0d3a5c1e-6f2b-4e8a-9b7d-4c1f2a3b5d6e",
  "fullName": "Alice Example",
  "phone": "9876543210",
  "dateOfBirth": "1990-01-01",
  "kycStatus": "PENDING"
}
```

---

## 2. Update my profile

| | |
| --- | --- |
| Method / URL | `PUT http://localhost:8080/api/v1/customers/me` |
| Direct URL | `PUT http://localhost:8082/api/v1/customers/me` |

**Headers**

| Header | Value | Required |
| --- | --- | --- |
| `Authorization` | `Bearer <accessToken>` | yes |
| `Content-Type` | `application/json` | yes |

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `fullName` | string | required, not blank, max 120 chars |
| `phone` | string | required, `+` optional then 10-15 digits |

**curl**

```bash
curl -i -X PUT "$BASE/api/v1/customers/me" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Alice Q. Example","phone":"+919999999999"}'
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 9c8b7a6d-5e4f-4321-a0b9-8c7d6e5f4a32
```

```json
{
  "id": "0d3a5c1e-6f2b-4e8a-9b7d-4c1f2a3b5d6e",
  "fullName": "Alice Q. Example",
  "phone": "+919999999999",
  "dateOfBirth": "1990-01-01",
  "kycStatus": "PENDING"
}
```

**Errors**

`400 Bad Request`: validation

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/customers/me",
  "code": "VALIDATION_FAILED",
  "errors": {
    "fullName": "must not be blank",
    "phone": "must be 10-15 digits"
  }
}
```

---

## 3. Submit KYC (simulated)

Verifies a document with the simulated KYC provider. A document number of 6-20 upper-case letters/digits that does not
start with `000` is `VERIFIED`; anything else is `REJECTED`. A rejected customer can submit again. KYC must be `VERIFIED`
before an account can be opened.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/customers/me/kyc` |
| Direct URL | `POST http://localhost:8082/api/v1/customers/me/kyc` |

**Headers**

| Header | Value | Required |
| --- | --- | --- |
| `Authorization` | `Bearer <accessToken>` | yes |
| `Content-Type` | `application/json` | yes |

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `documentType` | string | required, one of `PAN`, `AADHAAR`, `PASSPORT` |
| `documentNumber` | string | required |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/customers/me/kyc" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"documentType":"PAN","documentNumber":"ABCDE1234F"}'
```

**Success: `200 OK`** (verified)

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 2b1a0f9e-8d7c-4b6a-9594-3e2d1c0b9a87
```

```json
{
  "id": "0d3a5c1e-6f2b-4e8a-9b7d-4c1f2a3b5d6e",
  "fullName": "Alice Q. Example",
  "phone": "+919999999999",
  "dateOfBirth": "1990-01-01",
  "kycStatus": "VERIFIED"
}
```

Rejected document (`"documentNumber": "000999111"`) also returns `200`, with `"kycStatus": "REJECTED"`.

**Errors**

`409 Conflict`: already verified

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "KYC is already verified",
  "instance": "/api/v1/customers/me/kyc",
  "code": "KYC_ALREADY_VERIFIED"
}
```

`400 Bad Request`: `documentType` not one of the allowed values or blank `documentNumber`

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/customers/me/kyc",
  "code": "VALIDATION_FAILED",
  "errors": {
    "documentType": "must match \"PAN|AADHAAR|PASSPORT\"",
    "documentNumber": "must not be blank"
  }
}
```

---

## Related

- Service-to-service lookup used by banking-core: `GET /internal/customers/by-user/{userId}` in
  [internal-apis.md](internal-apis.md).
- Admin audit/outbox endpoints on the direct port `8082`: [admin-operations.md](admin-operations.md).
