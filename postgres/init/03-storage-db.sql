-- Storage's own database (MIG-53 part c, MIG-68): storage_connection and the key check live here,
-- and only storage-service connects to it. A fresh volume gets it from this script; an existing
-- one needs the same statement once.
CREATE DATABASE storage_db;
