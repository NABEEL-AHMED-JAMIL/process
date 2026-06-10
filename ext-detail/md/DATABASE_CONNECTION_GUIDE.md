# PostgreSQL Local Database Access Guide

## 📍 Connection Details

```
Host:           localhost
Port:           5432
Database:       etl_job
Username:       nabeel.amd93
Password:       admin
```

---

## 🔌 Connection Methods

### 1️⃣ Using Docker (psql)
```bash
docker exec postgres_db psql -U nabeel.amd93 -d etl_job
```

**Query Examples:**
```sql
-- Connect to database
\c etl_job

-- List all tables
\dt

-- View lookup_data
SELECT * FROM lookup_data;

-- View source_task_type
SELECT * FROM source_task_type;

-- View shedlock
SELECT * FROM shedlock;

-- Exit
\q
```

---

### 2️⃣ Using DBeaver or pgAdmin (GUI Tools)

**Steps:**
1. Open DBeaver / pgAdmin
2. Create new PostgreSQL connection with these details:
   - **Server:** localhost
   - **Port:** 5432
   - **Database:** etl_job
   - **Username:** nabeel.amd93
   - **Password:** admin

---

### 3️⃣ Using JDBC (Java)
```java
String url = "jdbc:postgresql://localhost:5432/etl_job";
String user = "nabeel.amd93";
String password = "admin";

Connection conn = DriverManager.getConnection(url, user, password);
```

**Maven Dependency:**
```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.4.3</version>
</dependency>
```

---

### 4️⃣ Using Python (psycopg2)
```bash
pip install psycopg2-binary
```

```python
import psycopg2

conn = psycopg2.connect(
    host='localhost',
    port=5432,
    database='etl_job',
    user='nabeel.amd93',
    password='admin'
)

cursor = conn.cursor()
cursor.execute('SELECT * FROM lookup_data;')
rows = cursor.fetchall()
for row in rows:
    print(row)

cursor.close()
conn.close()
```

---

### 5️⃣ Using Node.js (pg)
```bash
npm install pg
```

```javascript
const { Client } = require('pg');

const client = new Client({
  host: 'localhost',
  port: 5432,
  database: 'etl_job',
  user: 'nabeel.amd93',
  password: 'admin',
});

client.connect();

client.query('SELECT * FROM lookup_data;', (err, res) => {
  if (err) throw err;
  console.log(res.rows);
  client.end();
});
```

---

### 6️⃣ Using Command Line (psql Client)
```bash
# Install psql (if not already installed)
# macOS:
brew install libpq

# Then add to PATH (add this to ~/.zshrc or ~/.bash_profile):
export PATH="/usr/local/opt/libpq/bin:$PATH"

# Verify installation:
psql --version

# Connect to database:
psql -h localhost -U nabeel.amd93 -d etl_job -p 5432
```

---

## 📊 Useful SQL Queries

### View All Tables
```sql
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';
```

### View All Records in lookup_data
```sql
SELECT * FROM lookup_data;
```

### View All Records in source_task_type
```sql
SELECT * FROM source_task_type;
```

### View Database Statistics
```sql
SELECT 
  (SELECT COUNT(*) FROM lookup_data) as lookup_data_count,
  (SELECT COUNT(*) FROM source_task_type) as source_task_type_count,
  (SELECT COUNT(*) FROM shedlock) as shedlock_count;
```

### Backup Database
```bash
docker exec postgres_db pg_dump -U nabeel.amd93 -d etl_job > backup_$(date +%Y%m%d_%H%M%S).sql
```

### Restore Database
```bash
docker exec -i postgres_db psql -U nabeel.amd93 -d etl_job < backup_file.sql
```

---

## 🐳 Docker Commands for Database

### Start Database Only
```bash
docker-compose up -d postgres_db
```

### Stop Database
```bash
docker-compose down
```

### View Database Logs
```bash
docker-compose logs postgres_db
```

### Execute SQL File
```bash
docker exec -i postgres_db psql -U nabeel.amd93 -d etl_job < script.sql
```

### Access PostgreSQL Interactive Shell
```bash
docker exec -it postgres_db psql -U nabeel.amd93 -d etl_job
```

---

## ✅ Verification

Test your connection with these commands:

```bash
# View database version
docker exec postgres_db psql -U nabeel.amd93 -d etl_job -c "SELECT version();"

# View all tables
docker exec postgres_db psql -U nabeel.amd93 -d etl_job -c "\dt"

# View record counts
docker exec postgres_db psql -U nabeel.amd93 -d etl_job -c "SELECT COUNT(*) FROM lookup_data; SELECT COUNT(*) FROM source_task_type;"
```

---

## 🚀 Application Connection

The Spring Boot application connects using:
```properties
spring.datasource.url=jdbc:postgresql://postgres:5432/etl_job
spring.datasource.username=nabeel.amd93
spring.datasource.password=admin
```

The application uses the Docker service name `postgres` internally, but locally use `localhost`.

---

## 📋 Current Database Status

| Metric | Count |
|--------|-------|
| lookup_data records | 7 |
| source_task_type records | 11 |
| shedlock records | 0 |

---

## ⚠️ Important Notes

1. **Port Mapping:** Docker maps port 5432 (container) to 5432 (host)
2. **Credentials:** Username and password are the same for all connections
3. **Database Engine:** PostgreSQL 15
4. **Data Persistence:** Data is stored in the `postgres_data` Docker volume

---

## 🆘 Troubleshooting

### Cannot Connect to Database
1. Verify Docker containers are running: `docker-compose ps`
2. Check if port 5432 is already in use: `lsof -i :5432`
3. Verify PostgreSQL container is healthy: `docker-compose logs postgres_db`

### Permission Denied
- Check username and password are correct
- Verify Docker daemon is running

### Connection Timeout
- Ensure PostgreSQL container is running and healthy
- Check firewall settings
- Try connecting with: `psql -h 127.0.0.1` instead of `localhost`

