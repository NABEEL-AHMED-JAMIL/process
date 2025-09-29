# Check PostgreSQL Database (Docker & Host)

```bash
# 1️⃣ List running containers
docker ps

# 2️⃣ Connect to PostgreSQL container
docker exec -it postgres_db psql -U postgres

# 3️⃣ Inside psql, list databases
\l

# 4️⃣ Connect to your database
\c mydb

# 5️⃣ List tables (if any)
\dt

# 6️⃣ Exit psql
\q

# 7️⃣ Optional: Connect from host machine if psql is installed locally
psql -h localhost -p 5432 -U postgres -d mydb
# Password: admin
