CREATE TABLE IF NOT EXISTS shedlock (
  name VARCHAR(64) NOT NULL,
  lock_until TIMESTAMP(3) NULL,
  locked_at TIMESTAMP(3) NULL,
  locked_by VARCHAR(255),
  PRIMARY KEY (name)
);

-- Create sequence for lookup_data
CREATE SEQUENCE IF NOT EXISTS lookup_id_Seq START 1000;

-- Create lookup_data table
CREATE TABLE IF NOT EXISTS lookup_data (
  lookup_id BIGINT PRIMARY KEY,
  lookup_value TEXT,
  lookup_type VARCHAR(255) UNIQUE,
  description VARCHAR(255),
  date_created TIMESTAMP NOT NULL,
  parent_lookup_id BIGINT,
  FOREIGN KEY (parent_lookup_id) REFERENCES lookup_data(lookup_id)
);

-- Create sequence for source_task_type
CREATE SEQUENCE IF NOT EXISTS source_task_type_source_Seq START 1000;

-- Create source_task_type table
CREATE TABLE IF NOT EXISTS source_task_type (
  source_task_type_id BIGINT PRIMARY KEY,
  service_name VARCHAR(255) NOT NULL,
  description VARCHAR(255) NOT NULL,
  queue_topic_partition VARCHAR(255) NOT NULL,
  task_type_status VARCHAR(255),
  is_schema_register BOOLEAN DEFAULT false,
  schema_payload TEXT
);

