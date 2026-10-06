# SQL Script Reference - Update task_type_status

## 📝 Overview
This guide explains the SQL script that updates the `task_type_status` column for `source_task_type` table.

---

## 🔧 Script Location
```
src/main/resources/db/changelog/yaml/V4.0-update-task-type-status.yaml
```

---

## 📋 What the Script Does

### ChangeSet 1: Update All Records to ACTIVE
```sql
UPDATE source_task_type SET task_type_status = 'ACTIVE';
```
- Updates ALL source_task_type records to ACTIVE status

### ChangeSet 2: Update Specific Records to ACTIVE
```sql
UPDATE source_task_type SET task_type_status = 'ACTIVE' 
WHERE source_task_type_id IN (1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010);
```
- Updates specific task type IDs to ACTIVE status

---

## ✅ Current Status

| Metric | Value |
|--------|-------|
| Total Records | 2 |
| ACTIVE Status | 2 |
| Update Status | ✓ Complete |

### Records in Database
```
source_task_type_id | service_name      | task_type_status
                1000| Test Loop         | ACTIVE
                1012| Scrapping Tool    | ACTIVE
```

---

## 🚀 How to Create Custom UPDATE Scripts

### Option 1: Direct SQL Update (Quick Fix)
```bash
docker exec postgres_db psql -U nabeel.amd93 -d etl_job -c \
  "UPDATE source_task_type SET task_type_status = 'ACTIVE' WHERE source_task_type_id = 1000;"
```

### Option 2: Liquibase Migration (Recommended for Production)
Create a new file: `src/main/resources/db/changelog/yaml/V5.0-custom-update.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: custom-update-task-type
      author: your-name
      changes:
        - sql:
            sql: "UPDATE source_task_type SET task_type_status = 'ACTIVE' WHERE service_name LIKE '%your-service%';"
            comment: Custom update for specific services
```

Then add to `db.changelog-master.yaml`:
```yaml
  - include:
      file: db/changelog/yaml/V5.0-custom-update.yaml
```

---

## 📊 Verify Updates

### Check All Records
```bash
docker exec postgres_db psql -U nabeel.amd93 -d etl_job -c \
  "SELECT * FROM source_task_type;"
```

### Count Status Distribution
```bash
docker exec postgres_db psql -U nabeel.amd93 -d etl_job -c \
  "SELECT task_type_status, COUNT(*) FROM source_task_type GROUP BY task_type_status;"
```

### Filter by Status
```bash
docker exec postgres_db psql -U nabeel.amd93 -d etl_job -c \
  "SELECT * FROM source_task_type WHERE task_type_status = 'ACTIVE';"
```

---

## 🔄 Modify Existing Scripts

To modify the migration after it's been applied to production, add a NEW changeSet (do NOT modify existing ones). This ensures idempotency:

```yaml
databaseChangeLog:
  - changeSet:
      id: update-specific-service-status
      author: nabeel.amd93
      changes:
        - sql:
            sql: "UPDATE source_task_type SET task_type_status = 'DISABLE' WHERE service_name = 'Old Service';"
            comment: Disable old service
```

---

## 📚 Related Documentation
- **Liquibase**: https://docs.liquibase.com/
- **PostgreSQL**: https://www.postgresql.org/docs/
- **Application**: `http://localhost:9098/api/v1/actuator/health`

---

## ⚠️ Important Notes
1. **Never modify applied migrations** - Always create new changesets
2. **Test in dev first** - Before deploying to production
3. **Backup database** - Before running mass updates
4. **Use WHERE clauses** - To avoid accidental updates to all records

---

**Last Updated:** 2026-06-04
**Status:** ✅ All Updates Applied

