# Scheduler Engine

### Overview
This project is a **scheduler engine** designed to manage and execute source jobs efficiently.  
It leverages **Apache Kafka** for real-time stream processing, ensuring scalability, fault tolerance, and high throughput in job execution workflows.
```
Process have 5 type of scheduler
1. Mint (scheduler run mint ex => every 5 mint)
2. Hr (scheduler run hr ex => every 1 hr)
3. Daily (scheduler run daily base)
4. Weekly (scheduler run base weekly)
5. Monthly (scheduler run base monthly)
```

### Features
- **Job Scheduling** – Handles periodic, delayed, and priority-based job execution.
- **Real-Time Stream Processing** – Uses Kafka producers and consumers to process jobs as messages.
- **Scalability** – Distributed architecture for handling large volumes of jobs concurrently.
- **Fault Tolerance** – Kafka ensures message durability and recovery in case of failures.
- **Prioritized Task Execution** – Tasks can be queued and executed based on defined priorities.
- **Thread Pool Execution** – Uses `ThreadPoolExecutor` with a `PriorityBlockingQueue` for efficient job handling.

### Architecture
1. **Producer** – Sends job/task messages into Kafka topics.
2. **Scheduler Engine** – Pulls tasks, applies scheduling logic, and enqueues them for execution.
3. **Consumer** – Reads processed results or error events from Kafka.
4. **Executor** – Uses a thread pool to run prioritized tasks concurrently.

### Tech Stack
- **Java / Spring Boot** – Core backend engine
- **Apache Kafka** – Real-time messaging and stream processing
- **PostgreSQL** – (Optional) Metadata and job tracking persistence

### Use Cases
- ETL job scheduling and execution
- Data pipeline orchestration
- Real-time processing of streaming tasks
- Event-driven workflows

### Running the Project
```bash
# Clone repository
git clone https://github.com/NABEEL-AHMED-JAMIL/process/tree/split-mono-to-microservice

# Navigate into project
cd process

# Build project
mvn clean install

# Run application
mvn spring-boot:run
```

### ETL WorkFlow diagram
Below detail show the existing workflow of process.

#### 1. Old ETL Workflow diagram
![alt text](ext-detail/old-etl.png)
#### 2. New ETL Workflow diagram
![alt text](ext-detail/new-etl.png)


## 2 Helping Query for view and run this project
```
Note :- Before run this project execute the below script.
INSERT INTO lookup_data VALUES
('1001','2021-03-31 22:09:43.244','This Scheduler use for send the sourceJob into the queue','2021-04-01T00:16:34.567','SCHEDULER_LAST_RUN_TIME'),
('1002','2021-03-31 23:06:48.744','This Queue fetch size use to fetch the limit of data from db','25','QUEUE_FETCH_LIMIT');

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name)
VALUES ('1000', '[consumer test]', 'topic=test-topic&partitions=[*]', 'Test');

```

## Process Endpoint
List of endpoint with detail of endpoint
1. Endpoint use download batch file <br>
   http://localhost:9098/api/v1/bulk.json/downloadBatchSchedulerTemplateFile
2. Endpoint use upload batch file <br>
   http://localhost:9098/api/v1/bulk.json/uploadBatchSchedulerFile
3. Below image show the kafka structure which implement in the project.
To run the kafka use the below cmd
   1. Start Apache Zookeeper.<br>
      .\bin\windows\zookeeper-server-start.bat .\config\zookeeper.properties (for window) <br>
      ./zookeeper-server-start.sh ../config/zookeeper.properties
   2. Start the Kafka server.<br>
      .\bin\windows\kafka-server-start.bat .\config\server.properties (for window) <br>
      .\bin\windows\kafka-server-start.bat .\config\server.properties

To start the Kafka server.
   1. To create the cluster for below detail download and install the 'Kafka Offset Explorer'.
   ![alt text](ext-detail/Topic-Detail.png)

### Process DB-UML
Below image show the detail for database detail for process. <br>
![alt text](ext-detail/new-dbdesing.png)

### Monitoring app [Grafana and Paragraph]
Spring Boot Actuator is enabled in this project to provide production-ready features such as monitoring, health checks, metrics, and system information.
It exposes useful endpoints that allow developers and operators to observe the state of the application, debug issues, and integrate with monitoring tools like Prometheus and Grafana.
The exposed endpoints include application health, metrics, configuration properties, environment details, loggers, Liquibase migration status, scheduled tasks, and request mappings.
These endpoints make it easier to monitor application behavior in real-time and ensure smooth operation in production.
```
{
  "_links": {
    "self": {
      "href": "http://localhost:9098/api/v1/actuator",
      "templated": false
    },
    "beans": {
      "href": "http://localhost:9098/api/v1/actuator/beans",
      "templated": false
    },
    "caches-cache": {
      "href": "http://localhost:9098/api/v1/actuator/caches/{cache}",
      "templated": true
    },
    "caches": {
      "href": "http://localhost:9098/api/v1/actuator/caches",
      "templated": false
    },
    "health": {
      "href": "http://localhost:9098/api/v1/actuator/health",
      "templated": false
    },
    "info": {
      "href": "http://localhost:9098/api/v1/actuator/info",
      "templated": false
    },
    "conditions": {
      "href": "http://localhost:9098/api/v1/actuator/conditions",
      "templated": false
    },
    "shutdown": {
      "href": "http://localhost:9098/api/v1/actuator/shutdown",
      "templated": false
    },
    "configprops": {
      "href": "http://localhost:9098/api/v1/actuator/configprops",
      "templated": false
    },
    "env": {
      "href": "http://localhost:9098/api/v1/actuator/env",
      "templated": false
    },
    "liquibase": {
      "href": "http://localhost:9098/api/v1/actuator/liquibase",
      "templated": false
    },
    "loggers": {
      "href": "http://localhost:9098/api/v1/actuator/loggers",
      "templated": false
    },
    "heapdump": {
      "href": "http://localhost:9098/api/v1/actuator/heapdump",
      "templated": false
    },
    "threaddump": {
      "href": "http://localhost:9098/api/v1/actuator/threaddump",
      "templated": false
    },
    "prometheus": {
      "href": "http://localhost:9098/api/v1/actuator/prometheus",
      "templated": false
    },
    "metrics": {
      "href": "http://localhost:9098/api/v1/actuator/metrics",
      "templated": false
    },
    "scheduledtasks": {
      "href": "http://localhost:9098/api/v1/actuator/scheduledtasks",
      "templated": false
    },
    "mappings": {
      "href": "http://localhost:9098/api/v1/actuator/mappings",
      "templated": false
    }
  }
}
```

