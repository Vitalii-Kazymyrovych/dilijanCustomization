# UWC Dilijan — Gate & Cafeteria Reports

* **Emergency Presence** (who is on campus now, grouped by category)
* **Unrecognized (Noname)** list
* **Entry/Exit analytics** (optional, historical)
* **Cafeteria daily attendance** (Breakfast/Lunch/Dinner, unique per meal, Unknown separate)
  Exports: **PDF/XLSX**; supports Telegram delivery.

---

## 1) Emergency Presence (Gate)

**Functionality:** current on-campus people grouped by category (students/staff/outsourced/parents), A→Z, printable/exportable.

**Request**

```http
GET /evacuation/report?format=xlsx   # or pdf
```

**Response**

```
200 OK
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="evacuation_2025-11-06.xlsx"
```

---

## 2) Unrecognized (Noname)

**Functionality:** unknown faces with camera, time, image reference for instant emergency use.

**Request**

```http
GET /gate/noname?format=pdf          # or xlsx
```

**Response**

```
200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="noname_2025-11-06.pdf"
```

---

## 3) Entry/Exit Analytics (optional)

**Functionality:** historical entry/exit per person/category with filters.

**Request**

```http
GET /gate/entry-exit?from=2025-11-01T00:00:00Z&to=2025-11-06T23:59:59Z&listId=12&format=xlsx
```

**Response**

```
200 OK
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="entry_exit_2025-11-01_to_2025-11-06.xlsx"
```

---

## 4) Cafeteria — Daily Attendance

**Functionality:** counts **unique people once per meal window**; excludes outsourced; categories (UWCD Students / UWCD Staff / DIS Students); **Unknown** separate.

**Request**

```http
POST /cafeteria/build?date=2025-11-06&timezone=Europe/Kyiv&format=xlsx
```

**Response (example)**

```
200 OK
Content-Type: text/plain;charset=UTF-8
cafeteria_2025-11-06.xlsx saved to /app/exports/cafeteria_2025-11-06.xlsx
```

> Meal windows (default): Breakfast `06:30–10:30`, Lunch `11:30–14:30`, Dinner `18:00–20:30`; a person is counted **once per meal** even if detected multiple times.

---

## 5) Telegram Bot (reports via chat)

**Functionality:** generate and send reports to a Telegram chat/channel.

**Usage examples**

* **Cafeteria today → to Telegram**

  ```http
  POST /cafeteria/daily/send?format=xlsx
  ```

  **Response**

  ```
  200 OK
  Sent cafeteria_2025-11-06.xlsx to chat -1001234567890
  ```

* **Emergency presence → to Telegram**

  ```http
  POST /gate/presence/send?format=pdf
  ```

  **Response**

  ```
  200 OK
  Sent evacuation_2025-11-06.pdf to chat -1001234567890
  ```